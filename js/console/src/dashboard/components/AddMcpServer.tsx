import * as React from 'react';
import { useState } from 'react';
import {
    Box,
    Button,
    Card,
    CardContent,
    TextField,
    Typography,
    MenuItem,
    FormControl,
    InputLabel,
    Select,
    Alert,
} from '@mui/material';
import { useAuth0 } from '@auth0/auth0-react';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import DragonProxy from "../../dragon_proxy/DragonProxy";
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import SaveIcon from '@mui/icons-material/Save';
import { AuthType, TransportType } from "../../proto/dragon/turnstile/v1/turnstile_service";

interface AddMcpServerProps {
    onGoBack: () => void;
}

const validationSchema = Yup.object({
    name: Yup.string()
        .required('Name is required')
        .matches(/^[a-zA-Z0-9_-]+$/, 'Name can only contain letters, numbers, underscores, and hyphens'),
    url: Yup.string()
        .required('URL is required')
        .url('Must be a valid URL'),
    authType: Yup.mixed<AuthType>().required('Authentication type is required'),
    transportType: Yup.mixed<TransportType>().required('Transport type is required'),
    staticToken: Yup.string().when('authType', {
        is: AuthType.STATIC_HEADER,
        then: (schema) => schema.required('Static token is required when using Static Header authentication'),
        otherwise: (schema) => schema,
    }),
});

const AddMcpServer = ({ onGoBack }: AddMcpServerProps) => {
    const { user, isLoading, isAuthenticated, getAccessTokenSilently } = useAuth0();
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState(false);

    const formik = useFormik({
        initialValues: {
            name: '',
            url: '',
            authType: AuthType.NONE,
            transportType: TransportType.STREAMING_HTTP,
            staticToken: '',
        },
        validationSchema: validationSchema,
        onSubmit: async (values) => {
            setError(null);
            setSuccess(false);

            try {
                setSubmitting(true);
                const accessToken = await getAccessTokenSilently();

                await DragonProxy.getInstance().addMcpServerWithToken(
                    user!.sub!,
                    accessToken,
                    values.name,
                    values.url,
                    values.authType,
                    values.transportType,
                    values.authType === AuthType.STATIC_HEADER ? values.staticToken : undefined
                );

                setSuccess(true);
                // Wait a moment to show success message, then go back
                setTimeout(() => {
                    onGoBack();
                }, 1000);
            } catch (error: any) {
                console.error("Error adding MCP server: ", error.message);
                setError(`Failed to add MCP server: ${error.message}`);
            } finally {
                setSubmitting(false);
            }
        },
    });

    if (isLoading) {
        return <div>Loading...</div>;
    }

    if (!isAuthenticated || !user) {
        return <div>User not logged in</div>;
    }

    const getAuthTypeLabel = (type: AuthType): string => {
        switch (type) {
            case AuthType.NONE:
                return "None";
            case AuthType.DISCOVER:
                return "OAuth Discovery";
            case AuthType.STATIC_HEADER:
                return "Static Header";
            default:
                return "Unspecified";
        }
    };

    const getTransportTypeLabel = (type: TransportType): string => {
        switch (type) {
            case TransportType.STREAMING_HTTP:
                return "Streaming HTTP";
            case TransportType.UNSPECIFIED:
                return "Unspecified";
            default:
                return "Unspecified";
        }
    };

    return (
        <Box sx={{ width: '100%', maxWidth: { sm: '100%', md: '1700px' } }}>
            <Button
                variant="text"
                startIcon={<ArrowBackIcon />}
                onClick={onGoBack}
                sx={{ mb: 2 }}
                disabled={submitting}
            >
                Back to MCP Servers
            </Button>

            <Card>
                <CardContent>
                    <Typography variant="h4" component="div" gutterBottom>
                        Add MCP Server
                    </Typography>

                    {error && (
                        <Alert severity="error" sx={{ mt: 3, mb: 2 }}>
                            {error}
                        </Alert>
                    )}

                    {success && (
                        <Alert severity="success" sx={{ mt: 3, mb: 2 }}>
                            MCP Server added successfully! Redirecting...
                        </Alert>
                    )}

                    <Box component="form" onSubmit={formik.handleSubmit} sx={{ mt: 3 }}>
                        <Box mb={2}>
                            <TextField
                                fullWidth
                                id="name"
                                name="name"
                                label="Name"
                                value={formik.values.name}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                error={formik.touched.name && Boolean(formik.errors.name)}
                                helperText={formik.touched.name && formik.errors.name ? formik.errors.name : "A friendly name for this MCP server (letters, numbers, _, - only)"}
                                disabled={submitting}
                            />
                        </Box>

                        <Box mb={2}>
                            <TextField
                                fullWidth
                                id="url"
                                name="url"
                                label="URL"
                                value={formik.values.url}
                                onChange={formik.handleChange}
                                onBlur={formik.handleBlur}
                                error={formik.touched.url && Boolean(formik.errors.url)}
                                helperText={formik.touched.url && formik.errors.url ? formik.errors.url : "The URL of the MCP server"}
                                placeholder="https://example.com/mcp"
                                disabled={submitting}
                            />
                        </Box>

                        <Box mb={2}>
                            <FormControl
                                fullWidth
                                error={formik.touched.authType && Boolean(formik.errors.authType)}
                            >
                                <InputLabel id="auth-type-label">Authentication Type</InputLabel>
                                <Select
                                    labelId="auth-type-label"
                                    id="authType"
                                    name="authType"
                                    value={formik.values.authType}
                                    label="Authentication Type"
                                    onChange={formik.handleChange}
                                    onBlur={formik.handleBlur}
                                    disabled={submitting}
                                >
                                    <MenuItem value={AuthType.NONE}>
                                        {getAuthTypeLabel(AuthType.NONE)}
                                    </MenuItem>
                                    <MenuItem value={AuthType.DISCOVER}>
                                        {getAuthTypeLabel(AuthType.DISCOVER)}
                                    </MenuItem>
                                    <MenuItem value={AuthType.STATIC_HEADER}>
                                        {getAuthTypeLabel(AuthType.STATIC_HEADER)}
                                    </MenuItem>
                                </Select>
                            </FormControl>
                        </Box>

                        <Box mb={2}>
                            <FormControl
                                fullWidth
                                error={formik.touched.transportType && Boolean(formik.errors.transportType)}
                            >
                                <InputLabel id="transport-type-label">Transport Type</InputLabel>
                                <Select
                                    labelId="transport-type-label"
                                    id="transportType"
                                    name="transportType"
                                    value={formik.values.transportType}
                                    label="Transport Type"
                                    onChange={formik.handleChange}
                                    onBlur={formik.handleBlur}
                                    disabled={submitting}
                                >
                                    <MenuItem value={TransportType.STREAMING_HTTP}>
                                        {getTransportTypeLabel(TransportType.STREAMING_HTTP)}
                                    </MenuItem>
                                </Select>
                            </FormControl>
                        </Box>

                        {formik.values.authType === AuthType.STATIC_HEADER && (
                            <Box mb={2}>
                                <TextField
                                    fullWidth
                                    id="staticToken"
                                    name="staticToken"
                                    label="Static Token"
                                    value={formik.values.staticToken}
                                    onChange={formik.handleChange}
                                    onBlur={formik.handleBlur}
                                    error={formik.touched.staticToken && Boolean(formik.errors.staticToken)}
                                    helperText={formik.touched.staticToken && formik.errors.staticToken ? formik.errors.staticToken : "The static authentication token for this server"}
                                    type="password"
                                    disabled={submitting}
                                />
                            </Box>
                        )}

                        <Box sx={{ mt: 3, display: 'flex', gap: 2 }}>
                            <Button
                                type="submit"
                                variant="contained"
                                color="primary"
                                startIcon={<SaveIcon />}
                                disabled={submitting}
                            >
                                {submitting ? 'Adding...' : 'Add Server'}
                            </Button>

                            <Button
                                variant="outlined"
                                onClick={onGoBack}
                                disabled={submitting}
                            >
                                Cancel
                            </Button>
                        </Box>
                    </Box>
                </CardContent>
            </Card>
        </Box>
    );
};

export default AddMcpServer;
