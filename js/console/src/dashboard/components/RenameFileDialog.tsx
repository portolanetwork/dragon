import React from "react";
import { useFormik } from "formik";
import * as Yup from "yup";
import { Dialog, DialogActions, DialogContent, DialogTitle, Button, TextField } from "@mui/material";

interface RenameFileDialogProps {
    open: boolean;
    selectedRow: any;
    onCancel: () => void;
    onConfirm: (newName: string) => void;
}

const RenameFileDialog: React.FC<RenameFileDialogProps> = ({ open, selectedRow, onCancel, onConfirm }) => {
    const formik = useFormik({
        initialValues: {
            newName: selectedRow?.name || "",
        },
        enableReinitialize: true,
        validationSchema: Yup.object({
            newName: Yup.string()
                .required("File name is required")
                .matches(/^[^<>:"/\\|?*]+$/, "Invalid file name"),
        }),
        onSubmit: (values) => {
            onConfirm(values.newName.trim());
        },
    });

    return (
        <Dialog open={open} onClose={onCancel}>
            <DialogTitle>Rename: {selectedRow?.name}</DialogTitle>
            <form onSubmit={formik.handleSubmit}>
                <DialogContent>
                    <TextField
                        id="newName"
                        name="newName"
                        label="New Name"
                        fullWidth
                        value={formik.values.newName}
                        onChange={formik.handleChange}
                        onBlur={formik.handleBlur}
                        error={formik.touched.newName && Boolean(formik.errors.newName)}
                        helperText={formik.touched.newName && typeof formik.errors.newName === "string" ? formik.errors.newName : undefined}

                        //helperText={formik.touched.newName && formik.errors.newName}
                        autoFocus
                        onKeyDown={(e) => {
                            if (e.key === "Enter") {
                                formik.handleSubmit();
                                e.preventDefault();
                            }
                        }}
                    />
                </DialogContent>
                <DialogActions>
                    <Button onClick={onCancel} color="primary">
                        Cancel
                    </Button>
                    <Button type="submit" color="secondary" variant="contained">
                        Confirm
                    </Button>
                </DialogActions>
            </form>
        </Dialog>
    );
};

export default RenameFileDialog;