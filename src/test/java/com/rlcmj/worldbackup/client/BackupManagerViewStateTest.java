package com.rlcmj.worldbackup.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BackupManagerViewStateTest {
    @Test
    public void resizeClampsPageAndDropsSelectionOutsideVisiblePage() {
        BackupManagerViewState state = new BackupManagerViewState();
        state.select(9);
        state.setPage(3);

        state.reconcile(10, 4);

        assertEquals(2, state.getPage());
        assertEquals(9, state.getSelection());
        state.reconcile(10, 3);
        assertEquals(2, state.getPage());
        assertEquals(-1, state.getSelection());
    }

    @Test
    public void rowHitTestingRejectsClicksOutsideHorizontalBounds() {
        assertFalse(BackupManagerViewState.isRowHit(3, 40, 34, 43, 20, 300));
        assertFalse(BackupManagerViewState.isRowHit(317, 40, 34, 43, 20, 300));
        assertTrue(BackupManagerViewState.isRowHit(20, 40, 34, 43, 20, 300));
    }
}
