import { useState, useEffect } from 'react';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import { Box, Button, FormControl, FormControlLabel, InputLabel, MenuItem, Radio, RadioGroup, Select, TextField, Typography } from '@mui/material';
import { useAuthState } from 'react-firebase-hooks/auth';
import { auth } from '../../firebase';
import SpyderProxy from '../../spyder_proxy/SpyderProxy';
import { AuthEnum, AccessEnum } from '../../proto/portola/common/v1/objects';
import {EndpointRow} from "./types/EndpointRow";


const validationSchema = Yup.object({
    serverEndpoint: Yup.string().required('Server Endpoint is required'),
    clientEndpoint: Yup.string().required('Client Endpoint is required'),
    clientPort: Yup.number().required('Client Port is required').positive('Client Port must be positive').integer('Client Port must be an integer'),
    path: Yup.string().required('Path is required').matches(/^(\/[^\/ ]*)+\/?$/, 'Path must be a valid file or directory path'),
    accessType: Yup.number().oneOf([AccessEnum.READ_ONLY, AccessEnum.READ_WRITE]).required('Access Type is required'),
    authType: Yup.number().oneOf([AuthEnum.AUTH_USER_PASSWORD, AuthEnum.AUTH_USER_PUBLICKEY]).required('Auth Type is required'),
});

export default function CreateSftp({ endpointRows, onDialogClose }: {
    endpointRows: EndpointRow[],
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
            clientEndpoint: '',
            clientPort: 0,
            path: '',
            subPath: '',
            accessType: AccessEnum.READ_WRITE,
            authType: AuthEnum.AUTH_USER_PASSWORD,
        },
        // ...rest unchanged
        onSubmit: (values) => {
            const homedir = endpointRows.find(row => row.id === values.serverEndpoint)?.homedir || '';
            const fullPath = homedir.endsWith('/') || !values.subPath
                ? `${homedir}${values.subPath}`
                : `${homedir}/${values.subPath}`;
            createSftp(
                values.serverEndpoint,
                Number(values.clientPort),
                values.clientEndpoint,
                fullPath,
                values.accessType,
                values.authType
            );
            setOpen(false);
            onDialogClose();
        },
    });

    useEffect(() => {
        const selectedServer = endpointRows.find(row => row.id === formik.values.serverEndpoint);
        if (selectedServer && selectedServer.homedir) {
            formik.setFieldValue('path', selectedServer.homedir);
            formik.setFieldValue('subPath', '');
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [formik.values.serverEndpoint]);

    const handleClickOpen = () => {
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
        onDialogClose();
    };

    function getSelectedClientEndpointHomedir(clientEndpointId: string, endpointRows: { id: string, homedir: string }[]): string | undefined {
        const endpoint = endpointRows.find(row => row.id === clientEndpointId);
        return endpoint?.homedir;
    }

    const createSftp = async (
        serverEndpointId: string,
        clientPort: number,
        clientEndpointId: string,
        path: string,
        accessType: AccessEnum,
        authType: AuthEnum
    ) => {
        await SpyderProxy.getInstance()
            .createSftp(user, serverEndpointId, clientPort, clientEndpointId, path, accessType, authType)
            .then(() => {
                console.log(`Created SFTP for serverEndpoint ${serverEndpointId}`);
            })
            .catch((error) => {
                console.log(`Error creating SFTP for serverEndpoint ${serverEndpointId}: ${error.message}`);
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
                                    <span style={{ fontWeight: 'bold' }}>{row.hostname}</span>
                                    <span style={{ color: 'grey', marginLeft: '5px' }}>[{row.id}]</span>
                                </MenuItem>
                            ))}
                        </Select>
                        {formik.touched.serverEndpoint && formik.errors.serverEndpoint ? (
                            <div>{formik.errors.serverEndpoint}</div>
                        ) : null}
                    </FormControl>
                    {/* AuthType Dropdown */}
                    <FormControl fullWidth margin="dense" sx={{ mt: 2 }}>
                        <InputLabel>Auth Type</InputLabel>
                        <Select
                            id="authType"
                            name="authType"
                            value={formik.values.authType}
                            onChange={formik.handleChange}
                            onBlur={formik.handleBlur}
                            error={formik.touched.authType && Boolean(formik.errors.authType)}
                        >
                            <MenuItem value={AuthEnum.AUTH_USER_PASSWORD}>Password</MenuItem>
                            <MenuItem value={AuthEnum.AUTH_USER_PUBLICKEY}>Public Key</MenuItem>
                        </Select>
                        {formik.touched.authType && formik.errors.authType ? (
                            <div>{formik.errors.authType}</div>
                        ) : null}
                    </FormControl>

                    <Box mb={2}>
                        <Box display="flex" alignItems="center">
                            <TextField
                                margin="dense"
                                id="homedir"
                                label="Home Directory"
                                value={endpointRows.find(row => row.id === formik.values.serverEndpoint)?.homedir || ''}
                                InputProps={{ readOnly: true }}
                                sx={{ flex: 2, mr: 1 }}
                            />
                            <TextField
                                margin="dense"
                                id="subPath"
                                name="subPath"
                                label="Subpath (optional)"
                                type="text"
                                fullWidth
                                value={formik.values.subPath}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                error={formik.touched.subPath && Boolean(formik.errors.subPath)}
                                helperText={formik.touched.subPath && formik.errors.subPath}
                                sx={{ flex: 3 }}
                            />
                        </Box>
                    </Box>

                    <Box mb={2}>
                        <Typography variant="subtitle2">Access Type</Typography>
                        <FormControl component="fieldset">
                            <RadioGroup
                                id="accessType"
                                name="accessType"
                                value={formik.values.accessType}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                row
                            >
                                <FormControlLabel value={AccessEnum.READ_ONLY} control={<Radio />} label="Read Only" />
                                <FormControlLabel value={AccessEnum.READ_WRITE} control={<Radio />} label="Read Write" />
                            </RadioGroup>
                            {formik.touched.accessType && formik.errors.accessType ? (
                                <div>{formik.errors.accessType}</div>
                            ) : null}
                        </FormControl>
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
                                    <span style={{ fontWeight: 'bold' }}>{row.hostname}</span>
                                    <span style={{ color: 'grey', marginLeft: '5px' }}>[{row.id}]</span>
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