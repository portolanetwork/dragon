import * as React from 'react';
import { useEffect, useState } from 'react';
import { Box, Typography, Card, CardContent, Button, Chip } from '@mui/material';
import { useAuth0 } from '@auth0/auth0-react';
import DragonProxy, { McpServerRow } from "../../dragon_proxy/DragonProxy";
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import { AuthType } from "../../proto/dragon/turnstile/v1/turnstile_service";

interface McpServerDetailsProps {
    serverUuid: string;
    onGoBack: () => void;
}

const McpServerDetails = ({ serverUuid, onGoBack }: McpServerDetailsProps) => {
    const { user, isLoading, isAuthenticated, getAccessTokenSilently } = useAuth0();
    const [server, setServer] = useState<McpServerRow | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const fetchServerDetails = async () => {
            if (!isAuthenticated || !user) return;

            try {
                setLoading(true);
                const accessToken = await getAccessTokenSilently();
                const servers = await DragonProxy.getInstance().listMcpServersWithToken(user.sub!, accessToken);
                const foundServer = servers.find(s => s.uuid === serverUuid);
                setServer(foundServer || null);
            } catch (error: any) {
                console.error("Error fetching MCP server details: ", error.message);
            } finally {
                setLoading(false);
            }
        };

        fetchServerDetails();
    }, [serverUuid, user, isAuthenticated]);

    const getAuthTypeLabel = (authType: AuthType): string => {
        switch (authType) {
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
            >
                Back to MCP Servers
            </Button>

            <Card>
                <CardContent>
                    <Typography variant="h4" component="div" gutterBottom>
                        MCP Server Details
                    </Typography>

                    <Box mt={3}>
                        <Box display="flex" alignItems="center" mb={2}>
                            <Typography variant="h6" minWidth={180}>UUID</Typography>
                            <Typography variant="body1" sx={{ color: '#00bcd4', fontFamily: 'monospace' }}>
                                {server.uuid}
                            </Typography>
                        </Box>

                        <Box display="flex" alignItems="center" mb={2}>
                            <Typography variant="h6" minWidth={180}>Name</Typography>
                            <Typography variant="body1" sx={{ color: '#00bcd4' }}>
                                {server.name}
                            </Typography>
                        </Box>

                        <Box display="flex" alignItems="center" mb={2}>
                            <Typography variant="h6" minWidth={180}>URL</Typography>
                            <Typography variant="body1" sx={{ color: '#00bcd4', wordBreak: 'break-all' }}>
                                {server.url}
                            </Typography>
                        </Box>

                        <Box display="flex" alignItems="center" mb={2}>
                            <Typography variant="h6" minWidth={180}>Auth Type</Typography>
                            <Chip
                                label={getAuthTypeLabel(server.authType)}
                                color={server.authType === AuthType.NONE ? "default" : "primary"}
                            />
                        </Box>

                        <Box display="flex" alignItems="center" mb={2}>
                            <Typography variant="h6" minWidth={180}>Has Static Token</Typography>
                            <Chip
                                label={server.hasStaticToken ? "Yes" : "No"}
                                color={server.hasStaticToken ? "success" : "default"}
                            />
                        </Box>

                        {server.createdAt && (
                            <Box display="flex" alignItems="center" mb={2}>
                                <Typography variant="h6" minWidth={180}>Created</Typography>
                                <Typography variant="body1" sx={{ color: '#00bcd4' }}>
                                    {new Date(server.createdAt).toLocaleString()}
                                </Typography>
                            </Box>
                        )}

                        {server.updatedAt && (
                            <Box display="flex" alignItems="center" mb={2}>
                                <Typography variant="h6" minWidth={180}>Updated</Typography>
                                <Typography variant="body1" sx={{ color: '#00bcd4' }}>
                                    {new Date(server.updatedAt).toLocaleString()}
                                </Typography>
                            </Box>
                        )}
                    </Box>
                </CardContent>
            </Card>
        </Box>
    );
};

export default McpServerDetails;
