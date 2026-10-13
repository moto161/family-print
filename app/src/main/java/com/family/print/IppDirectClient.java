package com.family.print;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 极简 IPP 1.1 客户端：局域网直连 Pantum 打印机（Print-Job，application/octet-stream）。
 * 实测 P2200NW：631 开放，document-format 仅支持 image/urf + application/octet-stream（自动嗅探）。
 * 注意：打印机嵌入式 IPP 栈是单线程，长任务响应慢，必须用长超时等响应，禁止提前断连
 * （2026-10-09 实测 30s 超时断连会把服务线程挂死十几分钟）。
 */
public class IppDirectClient {

    /** 探测打印机 631 端口是否可达（直连前置条件），timeout 毫秒 */
    public static boolean isReachable(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 应用层存活探测：TCP 通但 IPP 服务挂死时（嵌入式单线程栈可能卡死），
     * isReachable 仍返回 true 会导致直连干等 120s。发一个最小 Get-Printer-Attributes，
     * 短超时内收到完整 IPP 响应才算「可用」。
     */
    public static boolean isAlive(String ip, int port) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ip, port), 2000);
            s.setSoTimeout(3000);

            byte[] probe = buildIppRequest(new byte[0], null, 0);
            // 覆盖 operation 为 Get-Printer-Attributes(0x000B)
            probe[2] = 0x00; probe[3] = 0x0B;

            String head = "POST /ipp/print HTTP/1.1\r\n"
                    + "Host: " + ip + ":" + port + "\r\n"
                    + "Content-Type: application/ipp\r\n"
                    + "Content-Length: " + probe.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream os = s.getOutputStream();
            os.write(head.getBytes("ISO-8859-1"));
            os.write(probe);
            os.flush();

            InputStream is = s.getInputStream();
            byte[] first = new byte[64];
            int total = 0, n;
            while (total < first.length && (n = is.read(first, total, first.length - total)) > 0) {
                total += n;
            }
            // 收到 HTTP 状态行即认为 IPP 服务存活
            return total > 12 && new String(first, 0, 12, "ISO-8859-1").startsWith("HTTP/1");
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * IPP Print-Job 直推打印数据（JPEG 等，打印机按 octet-stream 嗅探）。
     * @return null=成功；否则返回错误描述
     */
    public static String printJob(String ip, int port, byte[] data, String jobName, int copies) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), 5000);
            s.setSoTimeout(120_000); // 长超时等响应，绝不能提前断连

            byte[] body = buildIppRequest(data, jobName, copies);
            String head = "POST /ipp/print HTTP/1.1\r\n"
                    + "Host: " + ip + ":" + port + "\r\n"
                    + "Content-Type: application/ipp\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";

            OutputStream os = s.getOutputStream();
            os.write(head.getBytes("ISO-8859-1"));
            os.write(body);
            os.flush();

            // 读完整 HTTP 响应
            InputStream is = s.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                resp.write(buf, 0, n);
                // HTTP 响应很小（无 chunked），读到 Connection: close 关闭为止
            }
            byte[] r = resp.toByteArray();

            // 解析：找 \r\n\r\n 后的 IPP 体，取 status-code
            int sep = indexOf(r, "\r\n\r\n".getBytes("ISO-8859-1"));
            // status-code 在 ippStart+2..+3，读完需要 sep+8 字节（v1.79 修正原 sep+6 少算 2 字节）
            if (sep < 0 || r.length < sep + 8) return "IPP 响应不完整";
            int ippStart = sep + 4;
            int status = ((r[ippStart + 2] & 0xff) << 8) | (r[ippStart + 3] & 0xff);
            if (status == 0x0000) return null; // successful-ok
            return String.format("IPP 错误 0x%04x", status);
        } catch (Exception e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** 构造 IPP 1.1 Print-Job 请求体（octet-stream，带 copies/job-name） */
    private static byte[] buildIppRequest(byte[] data, String jobName, int copies) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try {
            o.write(new byte[]{0x01, 0x01});                 // IPP 1.1
            o.write(new byte[]{0x00, 0x02});                 // Print-Job
            o.write(u32(1));                                 // request-id
            o.write(0x01);                                   // operation-attributes-tag
            o.write(attr(0x47, "attributes-charset", "utf-8"));
            o.write(attr(0x48, "attributes-natural-language", "en"));
            // printer-uri 由调用方上下文补足（这里用占位 host，打印机不校验主机名一致性）
            o.write(attr(0x45, "printer-uri", "ipp://printer/ipp/print"));
            o.write(attr(0x42, "requesting-user-name", "family-print"));
            o.write(attr(0x49, "document-format", "application/octet-stream"));
            if (jobName != null) o.write(attr(0x42, "job-name", jobName));
            o.write(0x02);                                   // job-operations-tag
            if (copies > 1) o.write(attrRaw(0x21, "copies", u32raw(copies)));
            o.write(0x03);                                   // end-of-attributes
            o.write(data);                                   // 打印数据
        } catch (Exception ignored) { }
        return o.toByteArray();
    }

    private static byte[] attr(int tag, String name, String value) {
        return attrRaw(tag, name, value.getBytes());
    }

    /** integer 等二进制值属性 */
    private static byte[] attrRaw(int tag, String name, byte[] vb) {
        byte[] nb = name.getBytes();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        o.write((nb.length >> 8) & 0xff); o.write(nb.length & 0xff);
        o.write(nb, 0, nb.length);
        o.write((vb.length >> 8) & 0xff); o.write(vb.length & 0xff);
        o.write(vb, 0, vb.length);
        return o.toByteArray();
    }

    /** integer 属性值（4 字节大端） */
    private static byte[] u32raw(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] u32(int v) { return u32raw(v); }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
