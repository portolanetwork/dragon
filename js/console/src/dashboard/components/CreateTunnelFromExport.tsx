import * as React from 'react';
import {useState, useEffect} from 'react';
import {
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
    FormControl,
    Typography
} from '@mui/material';
import {useFormik} from 'formik';
import * as Yup from 'yup';
import {useAuthState} from "react-firebase-hooks/auth";
//import { firebaseAuth } from "../../firebase/firebaseConfig";
import {auth} from "../../firebase";

import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import {ExportedAssetRow} from "./types/ExportedAsssetRow";

const validationSchema = Yup.object({
    clientEndpoint: Yup.string().required('Client Endpoint is required'),
    serverAsset: Yup.string().required('Server Asset is required'),
    clientPort: Yup.number().required('Port is required').positive('Port must be positive').integer('Port must be an integer'),
    exportId: Yup.string().required('Export ID is required'),
});

export default function CreateTunnelFromExport({endpointRows, onDialogClose}: {
    endpointRows: { id: string, hostname: string }[],
    onDialogClose: () => void
}) {
    const [user, loading, error] = useAuthState(auth);
    const [open, setOpen] = useState(false);
    const [exportRows, setExportRows] = useState<{ id: string, fromUser: string }[]>([]);

    useEffect(() => {
        const fetchExports = async () => {
            if (user) {
                const exports = await SpyderProxy.getInstance().getAllConnectorExportsToMe(user);

                console.log("Exports: ", exports);

                setExportRows(exports.map((exp: ExportedAssetRow) => ({id: exp.id, fromUser: exp.fromUserEmail})));
            }
        };
        fetchExports();
    }, [user]);

    const formik = useFormik({
        initialValues: {
            clientEndpoint: '',
            serverAsset: 'type1',
            clientPort: 0,
            exportId: '',
        },
        validationSchema: validationSchema,
        onSubmit: (values) => {
            createTunnel(values.clientEndpoint, Number(values.clientPort), values.exportId);
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

    const createTunnel = async (clientEndpointId: string, port: number, exportId: string) => {
        if (!user) {
            console.error("User not logged in");
            return;
        }

        await SpyderProxy.getInstance()
            .createTunnelFromExport(user, clientEndpointId, port, exportId)
            .then(() => {
                console.log(`Created tunnel for clientEndpoint ${clientEndpointId}`);
            })
            .catch((error) => {
                console.log(`Error creating tunnel for clientEndpoint ${clientEndpointId}: ${error.message}`);
            });
    };

    return (
        <Box>
            <Box mb={2}/>
            <form onSubmit={formik.handleSubmit}>
                <Box mb={2}>
                    <Typography variant="subtitle2">Server</Typography>
                    <FormControl fullWidth margin="dense">
                        <InputLabel>Export ID</InputLabel>
                        <Select
                            id="exportId"
                            name="exportId"
                            value={formik.values.exportId}
                            onChange={formik.handleChange}
                            onBlur={formik.handleBlur}
                            error={formik.touched.exportId && Boolean(formik.errors.exportId)}
                        >
                            {exportRows.map((row) => (
                                <MenuItem key={row.id} value={row.id}>
                                    {row.id} ({row?.fromUser})
                                </MenuItem>
                            ))}
                        </Select>
                        {formik.touched.exportId && formik.errors.exportId ? (
                            <div>{formik.errors.exportId}</div>
                        ) : null}
                    </FormControl>
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
                            <MenuItem value="type1">TCP Server - Unknown port</MenuItem>
                        </Select>
                        {formik.touched.serverAsset && formik.errors.serverAsset ? (
                            <div>{formik.errors.serverAsset}</div>
                        ) : null}
                    </FormControl>
                </Box>
                <Box mt={2}>
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