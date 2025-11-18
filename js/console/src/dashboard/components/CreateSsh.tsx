import { useState } from 'react';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import { Box, Button, FormControl, InputLabel, MenuItem, Select, TextField, Typography } from '@mui/material';
import { useAuthState } from 'react-firebase-hooks/auth';
import { auth } from '../../firebase';
import SpyderProxy from '../../spyder_proxy/SpyderProxy';
import { AuthEnum } from '../../proto/portola/common/v1/objects';

const validationSchema = Yup.object({
    serverEndpoint: Yup.string().required('Server Endpoint is required'),
    clientEndpoint: Yup.string().required('Client Endpoint is required'),
    clientPort: Yup.number().required('Client Port is required').positive('Client Port must be positive').integer('Client Port must be an integer'),
    authType: Yup.number().oneOf([AuthEnum.AUTH_USER_PASSWORD, AuthEnum.AUTH_USER_PUBLICKEY]).required('Auth Type is required'),
});

export default function CreateSsh({ endpointRows, onDialogClose }: {
    endpointRows: { id: string, hostname: string }[],
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
            authType: AuthEnum.AUTH_USER_PASSWORD, // default value as enum
        },
        validationSchema: validationSchema,
        onSubmit: (values) => {
            console.log(`Creating SSH with serverEndpoint: ${values.serverEndpoint}, clientEndpoint: ${values.clientEndpoint}, clientPort: ${values.clientPort}, authType: ${values.authType}`);
            createSsh(values.serverEndpoint, Number(values.clientPort), values.clientEndpoint, values.authType);
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

    const createSsh = async (serverEndpointId: string, clientPort: number, clientEndpointId: string, authEnum: AuthEnum) => {
        await SpyderProxy.getInstance()
            .createSsh(user, serverEndpointId, clientPort, clientEndpointId, authEnum)
            .then(() => {
                console.log(`Created SFTP for serverEndpoint ${serverEndpointId}`);
            })
            .catch((error) => {
                console.log(`Error creating SSSH for serverEndpoint ${serverEndpointId}: ${error.message}`);
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