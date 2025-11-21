import * as React from 'react';
import { useEffect, useState } from 'react';
import { GridColDef, GridRowParams } from '@mui/x-data-grid';
import { Box, Chip, Divider, Typography, Card, CardContent, Button } from '@mui/material';
import { useAuth0 } from '@auth0/auth0-react';
import DragonProxy, { McpServerRow } from "../../dragon_proxy/DragonProxy";
import StyledDataGrid from "./StyledDataGrid";
import UUIDDisplay from './UUIDDisplay';
import SyncIcon from '@mui/icons-material/Sync';
import { AuthType, TransportType } from "../../proto/dragon/turnstile/v1/turnstile_service";

interface McpServersGridProps {
    onServerSelect: (serverUuid: string) => void;
    onAddServer: () => void;
    refreshTrigger?: number;
}

const McpServersGrid = ({ onServerSelect, onAddServer, refreshTrigger }: McpServersGridProps) => {
    const { user, isLoading, isAuthenticated, getAccessTokenSilently } = useAuth0();
    const [mcpServerRows, setMcpServerRows] = useState<McpServerRow[]>([]);

    if (isLoading) {
        return <div>Loading...</div>;
    }

    if (!isAuthenticated || !user) {
        console.error("User not logged in");
        return <div>User not logged in</div>;
    }

    const fetchMcpServers = async () => {
        try {
            const accessToken = await getAccessTokenSilently();
            const servers = await DragonProxy.getInstance().listMcpServersWithToken(user.sub!, accessToken);
            setMcpServerRows(servers);
        } catch (error: any) {
            console.error("Error fetching MCP servers: ", error.message);
        }
    };

    useEffect(() => {
        fetchMcpServers();
    }, [user, refreshTrigger]);

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

    const getTransportTypeLabel = (transportType: TransportType): string => {
        switch (transportType) {
            case TransportType.STREAMING_HTTP:
                return "Streaming HTTP";
            case TransportType.UNSPECIFIED:
                return "Unspecified";
            default:
                return "Unspecified";
        }
    };

    const columns: GridColDef[] = [
        {
            field: 'uuid',
            headerName: 'UUID',
            width: 200,
            renderCell: (params) => <UUIDDisplay uuid={params.value} />,
        },
        { field: 'name', headerName: 'Name', width: 200 },
        { field: 'url', headerName: 'URL', flex: 1, minWidth: 250 },
        {
            field: 'authType',
            headerName: 'Auth Type',
            width: 150,
            renderCell: (params) => (
                <Chip
                    label={getAuthTypeLabel(params.value)}
                    color={params.value === AuthType.NONE ? "default" : "primary"}
                    size="small"
                />
            ),
        },
        {
            field: 'transportType',
            headerName: 'Transport',
            width: 150,
            renderCell: (params) => (
                <Chip
                    label={getTransportTypeLabel(params.value)}
                    color={params.value === TransportType.STREAMING_HTTP ? "primary" : "default"}
                    size="small"
                />
            ),
        },
        {
            field: 'hasStaticToken',
            headerName: 'Static Token',
            width: 100,
            renderCell: (params) => (
                <Chip
                    label={params.value ? "Yes" : "No"}
                    color={params.value ? "success" : "default"}
                    size="small"
                />
            ),
        },
        {
            field: 'createdAt',
            headerName: 'Created',
            width: 180,
            renderCell: (params) => params.value ? new Date(params.value).toLocaleString() : '',
        },
    ];

    const handleRowClick = async (params: GridRowParams) => {
        onServerSelect(params.row.uuid);
    };

    const refreshAll = async () => {
        await fetchMcpServers();
    };

    return (
        <Box sx={{ width: '100%', maxWidth: { sm: '100%', md: '1700px' } }}>
            <Box display="flex" justifyContent="left" alignItems="center">
                <Button
                    variant="text"
                    color="primary"
                    onClick={onAddServer}
                    sx={{ padding: '10px 20px', borderRadius: '0' }}
                >
                    Add MCP Server
                </Button>
                <Divider orientation="vertical" variant="middle" flexItem />
                <Button
                    variant="text"
                    color="primary"
                    onClick={refreshAll}
                    startIcon={<SyncIcon />}
                    sx={{ padding: '10px 20px', borderRadius: '0' }}
                >
                    Refresh
                </Button>
            </Box>

            {mcpServerRows.length === 0 ? (
                <Box mt={10} textAlign="center">
                    <Typography variant="h6">No MCP servers configured</Typography>
                    <Divider orientation="vertical" variant="middle" flexItem />
                    <br />
                    <Typography variant="body2" color="text.secondary">
                        Add your first MCP server to get started
                    </Typography>
                </Box>
            ) : (
                <>
                    <Box mt={4} sx={{ '& .MuiTypography-root': { fontWeight: 'bold' } }}>
                        <Card>
                            <CardContent>
                                <Box display="flex" justifyContent="space-between" alignItems="center">
                                    <Typography variant="h5" component="div" gutterBottom>
                                        MCP Servers
                                    </Typography>
                                </Box>
                            </CardContent>
                        </Card>
                    </Box>
                    <div style={{ width: '100%' }}>
                        <StyledDataGrid
                            initialState={{
                                pagination: { paginationModel: { pageSize: 10 } }
                            }}
                            rows={mcpServerRows}
                            columns={columns}
                            getRowId={(row) => row.uuid}
                            pageSizeOptions={[5, 10, 25]}
                            pagination
                            onRowClick={handleRowClick}
                        />
                    </div>
                </>
            )}
        </Box>
    );
};

export default McpServersGrid;
