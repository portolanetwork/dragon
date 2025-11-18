import * as React from 'react';
import {useState} from 'react';
import {
    Typography,
    Button,
    Dialog,
    DialogActions,
    DialogContent,
    DialogTitle,
    TextField,
    Box,
    MenuItem,
    Select,
    InputLabel,
    FormControl
} from '@mui/material';
import {useFormik} from 'formik';
import * as Yup from 'yup';

import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";

import SpyderProxy from '../../spyder_proxy/SpyderProxy';
import {TunnelRow} from "./types/TunnelRow";

const validationSchema = Yup.object({
    serverEndpoint: Yup.string().required('Server Endpoint is required'),
    serverAsset: Yup.string().required('Server Asset is required'),
    serverPort: Yup.number().required('Server Port is required').positive('Server Port must be positive').integer('Server Port must be an integer'),
    clientEndpoint: Yup.string().required('Client Endpoint is required'),
    clientAsset: Yup.string().required('Client Asset is required'),
    clientPort: Yup.number().required('Client Port is required').positive('Client Port must be positive').integer('Client Port must be an integer'),
});

export default function CreateTunnel({endpointRows, tunnelRows, onDialogClose}: {
    endpointRows: { id: string, hostname: string }[],
    tunnelRows: TunnelRow[],
    onDialogClose: () => void
}) {
    const [user, loading, error] = useAuthState(auth);
    const [open, setOpen] = useState(false);

    if (!user) {
        throw new Error("User is not authenticated");
    }

    const formik = useFormik({
        initialValues: {
            serverEndpoint: '',
            serverAsset: 'type1', // Set default value here
            serverPort: 0,
            clientEndpoint: '',
            clientAsset: 'type1', // Set default value here
            clientPort: 0,
        },
        validationSchema: validationSchema,
        onSubmit: (values) => {
            /*
            const clientTunnelExists = tunnelRows.some(row => row.right.endpointId === values.clientEndpoint && row.right.Port === values.clientPort);
            if (clientTunnelExists) {
                alert('A tunnel with the same client endpoint and port already exists.');
                return;
            }
             */

            console.log(`Creating tunnel with serverEndpoint: ${values.serverEndpoint}, serverAsset: ${values.serverAsset}, serverPort: ${values.serverPort}, clientEndpoint: ${values.clientEndpoint}, clientAsset: ${values.clientAsset}, clientPort: ${values.clientPort}`);
            createTunnel(values.serverEndpoint, Number(values.serverPort), values.clientEndpoint, Number(values.clientPort));
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

    const createTunnel = async (serverEndpointId: string, serverPort: number, clientEndpointId: string, clientPort: number) => {
        await SpyderProxy.getInstance()
            .createTunnel(user, serverEndpointId, serverPort, clientEndpointId, clientPort)
            .then(() => {
                console.log(`Created tunnel for serverEndpoint ${serverEndpointId}`);
            })
            .catch((error) => {
                console.log(`Error creating tunnel for serverEndpoint ${serverEndpointId}: ${error.message}`);
            });
    };

    return (
        <Box>
            <Box mb={2} />
            <form onSubmit={formik.handleSubmit}>
                <Box mb={2}>
                    <Typography variant="subtitle2">Server</Typography>
                    <FormControl fullWidth margin="dense">
                        <InputLabel>Server Endpoint</InputLabel>
                        <Select
                            id="serverEndpoint"
                            name="serverEndpoint"
                            value={formik.values.serverEndpoint}
                            onChange={formik.handleChange}
                            onBlur={formik.handleBlur}
                            error={formik.touched.serverEndpoint && Boolean(formik.errors.serverEndpoint)}
                        >
                            {endpointRows.map((row) => (
                                <MenuItem key={row.id} value={row.id}>
                                    <span style={{fontWeight: 'bold'}}>{row.hostname}</span>
                                    <span style={{color: 'grey', marginLeft: '5px'}}>[{row.id}]</span>
                                </MenuItem>
                            ))}
                        </Select>
                        {formik.touched.serverEndpoint && formik.errors.serverEndpoint ? (
                            <div>{formik.errors.serverEndpoint}</div>
                        ) : null}
                    </FormControl>
                    <Box display="flex" gap={2}>
                        <FormControl fullWidth margin="dense">
                            <InputLabel>Server Asset</InputLabel>
                            <Select
                                id="serverAsset"
                                name="serverAsset"
                                value={formik.values.serverAsset}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                error={formik.touched.serverAsset && Boolean(formik.errors.serverAsset)}
                            >
                                <MenuItem value="type1">TCP Server</MenuItem>
                            </Select>
                            {formik.touched.serverAsset && formik.errors.serverAsset ? (
                                <div>{formik.errors.serverAsset}</div>
                            ) : null}
                        </FormControl>
                        <TextField
                            margin="dense"
                            id="serverPort"
                            name="serverPort"
                            label="Server Port"
                            type="number"
                            fullWidth
                            value={formik.values.serverPort}
                            onChange={formik.handleChange}
                            onBlur={formik.handleBlur}
                            error={formik.touched.serverPort && Boolean(formik.errors.serverPort)}
                            helperText={formik.touched.serverPort && formik.errors.serverPort}
                        />
                    </Box>
                </Box>

                <Box mb={2}>
                    <Typography variant="subtitle2">Client</Typography>
                    <FormControl fullWidth margin="dense">
                        <InputLabel>Client Endpoint</InputLabel>
                        <Select
                            id="clientEndpoint"
                            name="clientEndpoint"
                            value={formik.values.clientEndpoint}
                            onChange={formik.handleChange}
                            onBlur={formik.handleBlur}
                            error={formik.touched.clientEndpoint && Boolean(formik.errors.clientEndpoint)}
                        >
                            {endpointRows.map((row) => (
                                <MenuItem key={row.id} value={row.id}>
                                    <span style={{fontWeight: 'bold'}}>{row.hostname}</span>
                                    <span style={{color: 'grey', marginLeft: '5px'}}>[{row.id}]</span>
                                </MenuItem>
                            ))}
                        </Select>
                        {formik.touched.clientEndpoint && formik.errors.clientEndpoint ? (
                            <div>{formik.errors.clientEndpoint}</div>
                        ) : null}
                    </FormControl>
                    <Box display="flex" gap={2}>
                        <FormControl fullWidth margin="dense">
                            <InputLabel>Client Asset</InputLabel>
                            <Select
                                id="clientAsset"
                                name="clientAsset"
                                value={formik.values.clientAsset}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                error={formik.touched.clientAsset && Boolean(formik.errors.clientAsset)}
                            >
                                <MenuItem value="type1">TCP Client</MenuItem>
                            </Select>
                            {formik.touched.clientAsset && formik.errors.clientAsset ? (
                                <div>{formik.errors.clientAsset}</div>
                            ) : null}
                        </FormControl>
                        <TextField
                            margin="dense"
                            id="clientPort"
                            name="clientPort"
                            label="Client Port"
                            type="number"
                            fullWidth
                            value={formik.values.clientPort}
                            onChange={formik.handleChange}
                            onBlur={formik.handleBlur}
                            error={formik.touched.clientPort && Boolean(formik.errors.clientPort)}
                            helperText={formik.touched.clientPort && formik.errors.clientPort}
                        />
                    </Box>
                </Box>

                <Box display="flex" justifyContent="flex-end" mt={2}>
                    <Button onClick={handleClose} color="primary">
                        Cancel
                    </Button>
                    <Button type="submit" color="primary" sx={{ ml: 2 }}>
                        Create
                    </Button>
                </Box>
            </form>
        </Box>
    );
}