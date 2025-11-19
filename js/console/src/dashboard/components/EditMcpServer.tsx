import * as React from 'react';
import { useEffect, useState } from 'react';
import {
    Box,
    Typography,
    Card,
    CardContent,
    Button,
    TextField,
    Alert,
    Dialog,
    DialogTitle,
    DialogContent,
    DialogContentText,
    DialogActions,
} from '@mui/material';
import { useAuth0 } from '@auth0/auth0-react';
import DragonProxy, { McpServerRow } from "../../dragon_proxy/DragonProxy";
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DeleteIcon from '@mui/icons-material/Delete';
import LoginIcon from '@mui/icons-material/Login';
import LogoutIcon from '@mui/icons-material/Logout';
import { AuthType, TransportType, LoginStatus } from "../../proto/dragon/turnstile/v1/turnstile_service";

interface EditMcpServerProps {
    serverUuid: string;
    onGoBack: () => void;
}

const EditMcpServer = ({ serverUuid, onGoBack }: EditMcpServerProps) => {
    const { user, isLoading, isAuthenticated, getAccessTokenSilently } = useAuth0();
    const [server, setServer] = useState<McpServerRow | null>(null);
    const [loading, setLoading] = useState(true);
    const [deleting, setDeleting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
    const [loginStatus, setLoginStatus] = useState<LoginStatus>(LoginStatus.UNSPECIFIED);
    const [checkingLoginStatus, setCheckingLoginStatus] = useState(false);
    const [connecting, setConnecting] = useState(false);
    const [disconnecting, setDisconnecting] = useState(false);

    useEffect(() => {
        const fetchServerDetails = async () => {
            if (!isAuthenticated || !user) return;

            try {
                setLoading(true);
                const accessToken = await getAccessTokenSilently();
                const servers = await DragonProxy.getInstance().listMcpServersWithToken(user.sub!, accessToken);
                const foundServer = servers.find(s => s.uuid === serverUuid);
                setServer(foundServer || null);

                // If auth type is DISCOVER, fetch login status
                if (foundServer && foundServer.authType === AuthType.DISCOVER) {
                    setCheckingLoginStatus(true);
                    try {
                        const status = await DragonProxy.getInstance().getLoginStatusForMcpServerWithToken(
                            user.sub!,
                            accessToken,
                            serverUuid
                        );
                        setLoginStatus(status.status);
                    } catch (error: any) {
                        console.error("Error fetching login status: ", error.message);
                    } finally {
                        setCheckingLoginStatus(false);
                    }
                }
            } catch (error: any) {
                console.error("Error fetching MCP server details: ", error.message);
            } finally {
                setLoading(false);
            }
        };

        fetchServerDetails();
    }, [serverUuid, user, isAuthenticated]);

    const handleDeleteClick = () => {
        setDeleteDialogOpen(true);
    };

    const handleDeleteCancel = () => {
        setDeleteDialogOpen(false);
    };

    const handleDeleteConfirm = async () => {
        setDeleteDialogOpen(false);
        setError(null);

        if (!user?.sub) {
            setError('User not authenticated');
            return;
        }

        try {
            setDeleting(true);
            const accessToken = await getAccessTokenSilently();

            await DragonProxy.getInstance().removeMcpServerWithToken(user.sub, accessToken, serverUuid);

            // Go back after successful deletion
            onGoBack();
        } catch (error: any) {
            console.error("Error deleting MCP server: ", error.message);
            setError(`Failed to delete MCP server: ${error.message}`);
            setDeleting(false);
        }
    };

    const handleConnect = async () => {
        setError(null);

        if (!user?.sub) {
            setError('User not authenticated');
            return;
        }

        try {
            setConnecting(true);
            const accessToken = await getAccessTokenSilently();

            const loginUrl = await DragonProxy.getInstance().loginMcpServerWithToken(
                user.sub,
                accessToken,
                serverUuid
            );

            // Open login URL in new window
            window.open(loginUrl, '_blank');

            // Refresh login status after a short delay
            setTimeout(async () => {
                try {
                    const status = await DragonProxy.getInstance().getLoginStatusForMcpServerWithToken(
                        user.sub!,
                        accessToken,
                        serverUuid
                    );
                    setLoginStatus(status.status);
                } catch (error: any) {
                    console.error("Error refreshing login status: ", error.message);
                }
            }, 2000);
        } catch (error: any) {
            console.error("Error connecting to MCP server: ", error.message);
            setError(`Failed to connect: ${error.message}`);
        } finally {
            setConnecting(false);
        }
    };

    const handleDisconnect = async () => {
        setError(null);

        if (!user?.sub) {
            setError('User not authenticated');
            return;
        }

        try {
            setDisconnecting(true);
            const accessToken = await getAccessTokenSilently();

            await DragonProxy.getInstance().logoutMcpServerWithToken(
                user.sub,
                accessToken,
                serverUuid
            );

            // Update login status
            setLoginStatus(LoginStatus.NOT_AUTHENTICATED);
        } catch (error: any) {
            console.error("Error disconnecting from MCP server: ", error.message);
            setError(`Failed to disconnect: ${error.message}`);
        } finally {
            setDisconnecting(false);
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

    const getLoginStatusLabel = (status: LoginStatus): string => {
        switch (status) {
            case LoginStatus.AUTHENTICATED:
                return "Connected";
            case LoginStatus.NOT_AUTHENTICATED:
                return "Not Connected";
            case LoginStatus.EXPIRED:
                return "Expired";
            case LoginStatus.NOT_APPLICABLE:
                return "Not Applicable";
            default:
                return "Unknown";
        }
    };

    if (isLoading || loading) {
        return <div>Loading...</div>;
    }

    if (!server) {
        return (
            <Box sx={{ width: '100%', maxWidth: { sm: '100%', md: '1700px' } }}>
                <Button
                    variant="text"
                    startIcon={<ArrowBackIcon />}
                    onClick={onGoBack}
                    sx={{ mb: 2 }}
                >
                    Back to MCP Servers
                </Button>
                <Typography variant="h6">Server not found</Typography>
            </Box>
        );
    }

    return (
        <Box sx={{ width: '100%', maxWidth: { sm: '100%', md: '1700px' } }}>
            <Button
                variant="text"
                startIcon={<ArrowBackIcon />}
                onClick={onGoBack}
                sx={{ mb: 2 }}
                disabled={deleting}
            >
                Back to MCP Servers
            </Button>

            <Card>
                <CardContent>
                    <Typography variant="h4" component="div" gutterBottom>
                        MCP Server Details
                    </Typography>

                    {error && (
                        <Alert severity="error" sx={{ mt: 3, mb: 2 }}>
                            {error}
                        </Alert>
                    )}

                    <Box sx={{ mt: 3 }}>
                        <Box mb={2}>
                            <TextField
                                fullWidth
                                label="UUID"
                                value={server.uuid}
                                disabled
                                helperText="Server UUID (read-only)"
                            />
                        </Box>

                        <Box mb={2}>
                            <TextField
                                fullWidth
                                label="Name"
                                value={server.name}
                                disabled
                            />
                        </Box>

                        <Box mb={2}>
                            <TextField
                                fullWidth
                                label="URL"
                                value={server.url}
                                disabled
                            />
                        </Box>

                        <Box mb={2}>
                            <TextField
                                fullWidth
                                label="Authentication Type"
                                value={getAuthTypeLabel(server.authType)}
                                disabled
                            />
                        </Box>

                        <Box mb={2}>
                            <TextField
                                fullWidth
                                label="Transport Type"
                                value={getTransportTypeLabel(server.transportType)}
                                disabled
                            />
                        </Box>

                        <Box mb={2}>
                            <TextField
                                fullWidth
                                label="Has Static Token"
                                value={server.hasStaticToken ? "Yes" : "No"}
                                disabled
                            />
                        </Box>

                        {server.authType === AuthType.DISCOVER && (
                            <Box mb={2}>
                                <TextField
                                    fullWidth
                                    label="Connection Status"
                                    value={checkingLoginStatus ? "Checking..." : getLoginStatusLabel(loginStatus)}
                                    disabled
                                />
                            </Box>
                        )}

                        {server.createdAt && (
                            <Box mb={2}>
                                <TextField
                                    fullWidth
                                    label="Created"
                                    value={new Date(server.createdAt).toLocaleString()}
                                    disabled
                                />
                            </Box>
                        )}

                        {server.updatedAt && (
                            <Box mb={2}>
                                <TextField
                                    fullWidth
                                    label="Updated"
                                    value={new Date(server.updatedAt).toLocaleString()}
                                    disabled
                                />
                            </Box>
                        )}

                        <Box sx={{ mt: 3, display: 'flex', gap: 2, flexWrap: 'wrap' }}>
                            {server.authType === AuthType.DISCOVER && (
                                <>
                                    {loginStatus === LoginStatus.AUTHENTICATED ? (
                                        <Button
                                            variant="contained"
                                            color="warning"
                                            startIcon={<LogoutIcon />}
                                            onClick={handleDisconnect}
                                            disabled={disconnecting || connecting || deleting}
                                        >
                                            {disconnecting ? 'Disconnecting...' : 'Disconnect'}
                                        </Button>
                                    ) : (
                                        <Button
                                            variant="contained"
                                            color="success"
                                            startIcon={<LoginIcon />}
                                            onClick={handleConnect}
                                            disabled={connecting || disconnecting || deleting}
                                        >
                                            {connecting ? 'Connecting...' : 'Connect'}
                                        </Button>
                                    )}
                                </>
                            )}

                            <Button
                                variant="contained"
                                color="error"
                                startIcon={<DeleteIcon />}
                                onClick={handleDeleteClick}
                                disabled={deleting || connecting || disconnecting}
                            >
                                {deleting ? 'Deleting...' : 'Delete'}
                            </Button>

                            <Button
                                variant="outlined"
                                onClick={onGoBack}
                                disabled={deleting || connecting || disconnecting}
                            >
                                Cancel
                            </Button>
                        </Box>
                    </Box>
                </CardContent>
            </Card>

            {/* Delete Confirmation Dialog */}
            <Dialog
                open={deleteDialogOpen}
                onClose={handleDeleteCancel}
            >
                <DialogTitle>Delete MCP Server</DialogTitle>
                <DialogContent>
                    <DialogContentText>
                        Are you sure you want to delete "{server.name}"? This action cannot be undone.
                    </DialogContentText>
                </DialogContent>
                <DialogActions>
                    <Button onClick={handleDeleteCancel} color="primary">
                        Cancel
                    </Button>
                    <Button onClick={handleDeleteConfirm} color="error" variant="contained">
                        Delete
                    </Button>
                </DialogActions>
            </Dialog>
        </Box>
    );
};

export default EditMcpServer;
