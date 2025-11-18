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
import DragonProxy from "../../dragon_proxy/DragonProxy";
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import SaveIcon from '@mui/icons-material/Save';
import { AuthType } from "../../proto/dragon/turnstile/v1/turnstile_service";

interface AddMcpServerProps {
    onGoBack: () => void;
}

const AddMcpServer = ({ onGoBack }: AddMcpServerProps) => {
    const { user, isLoading, isAuthenticated, getAccessTokenSilently } = useAuth0();
    const [name, setName] = useState('');
    const [url, setUrl] = useState('');
    const [authType, setAuthType] = useState<AuthType>(AuthType.NONE);
    const [staticToken, setStaticToken] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [success, setSuccess] = useState(false);

    if (isLoading) {
        return <div>Loading...</div>;
    }

    if (!isAuthenticated || !user) {
        return <div>User not logged in</div>;
    }

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);
        setSuccess(false);

        if (!name || !url) {
            setError('Name and URL are required');
            return;
        }

        if (authType === AuthType.STATIC_HEADER && !staticToken) {
            setError('Static token is required when using Static Header authentication');
            return;
        }

        try {
            setSubmitting(true);
            const accessToken = await getAccessTokenSilently();

            await DragonProxy.getInstance().addMcpServerWithToken(
                user.sub!,
                accessToken,
                name,
                url,
                authType,
                authType === AuthType.STATIC_HEADER ? staticToken : undefined
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
    };

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

                    <Box component="form" onSubmit={handleSubmit} sx={{ mt: 3 }}>
                        <Box mb={2}>
                            <TextField
                                fullWidth
                                label="Name"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                required
                                disabled={submitting}
                                helperText="A friendly name for this MCP server"
                            />
                        </Box>

                        <Box mb={2}>
                            <TextField
                                fullWidth
                                label="URL"
                                value={url}
                                onChange={(e) => setUrl(e.target.value)}
                                required
                                disabled={submitting}
                                helperText="The URL of the MCP server"
                                placeholder="https://example.com/mcp"
                            />
                        </Box>

                        <Box mb={2}>
                            <FormControl fullWidth>
                                <InputLabel id="auth-type-label">Authentication Type</InputLabel>
                                <Select
                                    labelId="auth-type-label"
                                    value={authType}
                                    label="Authentication Type"
                                    onChange={(e) => setAuthType(e.target.value as AuthType)}
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

                        {authType === AuthType.STATIC_HEADER && (
                            <Box mb={2}>
                                <TextField
                                    fullWidth
                                    label="Static Token"
                                    value={staticToken}
                                    onChange={(e) => setStaticToken(e.target.value)}
                                    required={authType === AuthType.STATIC_HEADER}
                                    disabled={submitting}
                                    helperText="The static authentication token for this server"
                                    type="password"
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
