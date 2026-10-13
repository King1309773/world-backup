package com.rlcmj.worldbackup.client;

final class BackupManagerViewState {
    private int page;
    private int selection = -1;

    int getPage() { return page; }
    int getSelection() { return selection; }
    void setPage(int page) { this.page = page; }
    void select(int selection) { this.selection = selection; }

    void reconcile(int itemCount, int visibleRows) {
        int rows = Math.max(1, visibleRows);
        int pages = Math.max(1, (Math.max(0, itemCount) + rows - 1) / rows);
        page = Math.max(0, Math.min(page, pages - 1));
        int first = page * rows;
        int last = Math.min(itemCount, first + rows);
        if (selection < first || selection >= last) selection = -1;
    }

    static boolean isRowHit(int mouseX, int mouseY, int firstY, int rowHeight,
                            int left, int right) {
        return mouseX >= left && mouseX < right && mouseY >= firstY
            && rowHeight > 0;
    }
}
