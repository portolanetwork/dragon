import * as React from 'react';
import { useState } from 'react';
import { Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField, Box, MenuItem, Select, InputLabel, FormControl } from '@mui/material';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import { useAuthState } from "react-firebase-hooks/auth";
import IosShareIcon from '@mui/icons-material/IosShare';
import { auth } from "../../firebase";


import SpyderProxy from '../../spyder_proxy/SpyderProxy';
import {EndpointRow} from "./types/EndpointRow";

const validationSchema = (userEmail: string) => Yup.object({
    assetType: Yup.string().required('Asset Type is required'),
    endpoint: Yup.string().required('Endpoint is required'),
    port: Yup.number().required('Port is required').positive('Port must be positive').integer('Port must be an integer'),
    email: Yup.string().email('Invalid email format').required('Email is required').notOneOf([userEmail], 'Cannot export to self'),
});

const errorTextStyle = {
    color: 'red',
    fontSize: '0.875rem',
    marginTop: '0.25rem',
};

export default function CreateExport({ endpointRows, userEmail, onDialogClose }: {
    endpointRows: EndpointRow[],
    userEmail: string, onDialogClose: () => void
}) {
    const [user, loading, error] = useAuthState(auth);
    const [open, setOpen] = useState(false);

    if (!user) {
        throw new Error("User is not authenticated");
    }

    const formik = useFormik({
        initialValues: {
            assetType: 'type1', // Set default value here
            endpoint: '',
            port: '',
            email: '',
        },
        validationSchema: validationSchema(userEmail),
        onSubmit: (values) => {
            console.log(`Creating asset with type: ${values.assetType}, endpoint: ${values.endpoint}, email: ${values.email}`);
            createExport(values.endpoint, Number(values.port), values.email);
            setOpen(false);
            onDialogClose();
        },
    });

    const handleClickOpen = () => {
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
        onDialogClose();
    };

    const createExport = async (endpointId: string, port: number, email: string) => {
        await SpyderProxy.getInstance()
            .createConnectorExport(user, endpointId, port, email)
            .then(() => {
                console.log(`Created export for endpoint ${endpointId}`);
            })
            .catch((error) => {
                console.log(`Error creating export for endpoint ${endpointId}: ${error.message}`);
            });
    };

    return (
        <Box>
            <Button variant="text" color="primary" onClick={handleClickOpen} sx={{ padding: '10px 20px', borderRadius: '0' }} startIcon={<IosShareIcon />}>
                Create Export
            </Button>
            <Dialog open={open} onClose={handleClose} sx={{ '& .MuiDialog-paper': { width: '450px' } }}>
                <DialogTitle>Create New Asset</DialogTitle>
                <DialogContent>
                    <form onSubmit={formik.handleSubmit}>
                        <FormControl fullWidth margin="dense">
                            <InputLabel>Endpoint</InputLabel>
                            <Select
                                id="endpoint"
                                name="endpoint"
                                value={formik.values.endpoint}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                error={formik.touched.endpoint && Boolean(formik.errors.endpoint)}
                            >
                                {endpointRows.map((row) => (
                                    <MenuItem key={row.id} value={row.id}>
                                        <span style={{ fontWeight: 'bold' }}>{row.hostname}</span>
                                        <span style={{ color: 'grey', marginLeft: '5px' }}>[{row.id}]</span>
                                    </MenuItem>
                                ))}
                            </Select>
                            {formik.touched.endpoint && formik.errors.endpoint ? (
                                <div style={errorTextStyle}>{formik.errors.endpoint}</div>
                            ) : null}
                        </FormControl>
                        <FormControl fullWidth margin="dense">
                            <InputLabel>Asset Type</InputLabel>
                            <Select
                                id="assetType"
                                name="assetType"
                                value={formik.values.assetType}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                error={formik.touched.assetType && Boolean(formik.errors.assetType)}
                            >
                                <MenuItem value="type1">TCP Server</MenuItem>
                            </Select>
                            {formik.touched.assetType && formik.errors.assetType ? (
                                <div style={errorTextStyle}>{formik.errors.assetType}</div>
                            ) : null}
                        </FormControl>
                        <TextField
                            margin="dense"
                            id="port"
                            name="port"
                            label="Port"
                            type="number"
                            fullWidth
                            value={formik.values.port}
                            onChange={formik.handleChange}
                            onBlur={formik.handleBlur}
                            error={formik.touched.port && Boolean(formik.errors.port)}
                            helperText={formik.touched.port && formik.errors.port}
                            FormHelperTextProps={{ style: errorTextStyle }}
                        />
                        <TextField
                            margin="dense"
                            id="email"
                            name="email"
                            label="Email"
                            type="email"
                            fullWidth
                            value={formik.values.email}
                            onChange={formik.handleChange}
                            onBlur={formik.handleBlur}
                            error={formik.touched.email && Boolean(formik.errors.email)}
                            helperText={formik.touched.email && formik.errors.email}
                            FormHelperTextProps={{ style: errorTextStyle }}
                        />
                        <DialogActions>
                            <Button onClick={handleClose} color="primary">
                                Cancel
                            </Button>
                            <Button type="submit" color="primary">
                                Create
                            </Button>
                        </DialogActions>
                    </form>
                </DialogContent>
            </Dialog>
        </Box>
    );
}