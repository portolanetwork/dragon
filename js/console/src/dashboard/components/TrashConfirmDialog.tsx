import React from "react";
import { Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Button } from "@mui/material";

interface TrashConfirmDialogProps {
    open: boolean;
    selectedRow: any;
    onCancel: () => void;
    onConfirm: () => void;
}

const TrashConfirmDialog: React.FC<TrashConfirmDialogProps> = ({ open, selectedRow, onCancel, onConfirm }) => {
    return (
        <Dialog open={open} onClose={onCancel}>
            <DialogTitle>Confirm Trash</DialogTitle>
            <DialogContent>
                <DialogContentText>
                    Are you sure you want to move "{selectedRow?.name}" to trash?
                </DialogContentText>
            </DialogContent>
            <DialogActions>
                <Button onClick={onCancel} color="primary">
                    Cancel
                </Button>
                <Button onClick={onConfirm} color="secondary" autoFocus>
                    Confirm
                </Button>
            </DialogActions>
        </Dialog>
    );
};

export default TrashConfirmDialog;