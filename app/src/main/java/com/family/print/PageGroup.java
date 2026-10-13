package com.family.print;

import java.util.ArrayList;

/**
 * 打印文件分组：一个文件对应一个PageGroup
 * 包含文件信息、页数、预览图URL、每页方向等
 */
public class PageGroup {
    String fileName;
    String pdfId;
    String localImgPath;
    int pageCount;
    int startPageIndex;
    ArrayList<Integer> orientations;
    ArrayList<String> previews;
}
