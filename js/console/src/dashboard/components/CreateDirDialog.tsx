import * as React from 'react';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import { Dialog, DialogContent, DialogTitle, Box, Button, TextField } from '@mui/material';
import CreateNewFolderIcon from "@mui/icons-material/CreateNewFolder";

export default function CreateDirDialog({ onCreate /*, onCancel */ }: {
    onCreate: (dirName: string) => void,
    //onCancel: () => void
}) {
    const [open, setOpen] = React.useState(false);

    const validationSchema = Yup.object({
        dirName: Yup.string()
            .required('Directory name is required')
            .matches(/^[^<>:"/\\|?*]+$/, 'Invalid directory name'),
    });

    const formik = useFormik({
        initialValues: {
            dirName: '',
        },
        validationSchema: validationSchema,
        onSubmit: (values) => {
            onCreate(values.dirName.trim());
            setOpen(false);
        },
    });

    const handleClickOpen = () => {
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
        //onCancel();
    };

    return (
        <Box>
            <Button
                variant="contained"
                color="secondary"
                size="small"
                startIcon={<CreateNewFolderIcon />}
                onClick={handleClickOpen}
                sx={{ padding: '2px 8px', height: '12px' ,minHeight: '24px', fontSize: '0.75rem' }}
            >
                Create Dir
            </Button>
            <Dialog open={open} onClose={handleClose} sx={{ '& .MuiDialog-paper': { width: '400px' } }}>
                <DialogTitle>Create Directory</DialogTitle>
                <DialogContent>
                    <form onSubmit={formik.handleSubmit}>
                        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 2 }}>
                            <TextField
                                id="dirName"
                                name="dirName"
                                label="Directory Name"
                                variant="outlined"
                                size="small"
                                fullWidth
                                value={formik.values.dirName}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                error={formik.touched.dirName && Boolean(formik.errors.dirName)}
                                helperText={formik.touched.dirName && formik.errors.dirName}
                            />
                            <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
                                <Button onClick={handleClose} color="secondary">Cancel</Button>
                                <Button type="submit" color="primary" variant="contained">Create</Button>
                            </Box>
                        </Box>
                    </form>
                </DialogContent>
            </Dialog>
        </Box>
    );
}