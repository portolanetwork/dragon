import * as React from 'react';
import { useEffect, useState } from 'react';
import { GridColDef, GridRowParams } from '@mui/x-data-grid';
import { Box, Chip, Divider, Typography, Card, CardContent, Button } from '@mui/material';
import { useAuthState } from 'react-firebase-hooks/auth';
import { auth } from "../../firebase";
import DragonProxy, { McpServerRow } from "../../dragon_proxy/DragonProxy";
import StyledDataGrid from "./StyledDataGrid";
import UUIDDisplay from './UUIDDisplay';
import SyncIcon from '@mui/icons-material/Sync';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked';
import { AuthType } from "../../proto/dragon/turnstile/v1/turnstile_service";

const McpServersGrid = () => {
    const [user, loading, error] = useAuthState(auth);
    const [mcpServerRows, setMcpServerRows] = useState<McpServerRow[]>([]);
    const [selectedServer, setSelectedServer] = useState<McpServerRow | null>(null);

    if (loading) {
        return <div>Loading...</div>;
    }

    if (!user) {
        console.error("User not logged in");
        return <div>User not logged in</div>;
    }

    const fetchMcpServers = async () => {
        try {
            const servers = await DragonProxy.getInstance().listMcpServers(user);
            setMcpServerRows(servers);
        } catch (error: any) {
            console.error("Error fetching MCP servers: ", error.message);
        }
    };

    useEffect(() => {
        fetchMcpServers();
    }, [user]);

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

    const columns: GridColDef[] = [
        {
            field: 'selected',
            headerName: '',
            width: 50,
            renderCell: (params) => (
                params.row.uuid === selectedServer?.uuid ? <CheckCircleOutlineIcon color="primary" /> : <RadioButtonUncheckedIcon />
            ),
        },
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
            field: 'hasStaticToken',
            headerName: 'Has Token',
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
        setSelectedServer(params.row);
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

            {selectedServer && (
                <>
                    <br />
                    <Card sx={{ border: '2px solid', borderColor: 'primary.main' }}>
                        <CardContent>
                            <Typography variant="h5" component="div" gutterBottom>
                                MCP Server Details
                            </Typography>
                            <Box display="flex" alignItems="center" mb={1}>
                                <Typography minWidth={120}>UUID</Typography>
                                <Typography sx={{ color: '#00bcd4' }}>{selectedServer.uuid}</Typography>
                            </Box>
                            <Box display="flex" alignItems="center" mb={1}>
                                <Typography minWidth={120}>Name</Typography>
                                <Typography sx={{ color: '#00bcd4' }}>{selectedServer.name}</Typography>
                            </Box>
                            <Box display="flex" alignItems="center" mb={1}>
                                <Typography minWidth={120}>URL</Typography>
                                <Typography sx={{ color: '#00bcd4' }}>{selectedServer.url}</Typography>
                            </Box>
                            <Box display="flex" alignItems="center" mb={1}>
                                <Typography minWidth={120}>Auth Type</Typography>
                                <Typography sx={{ color: '#00bcd4' }}>{getAuthTypeLabel(selectedServer.authType)}</Typography>
                            </Box>
                            <Box display="flex" alignItems="center" mb={1}>
                                <Typography minWidth={120}>Has Static Token</Typography>
                                <Typography sx={{ color: '#00bcd4' }}>{selectedServer.hasStaticToken ? "Yes" : "No"}</Typography>
                            </Box>
                            {selectedServer.createdAt && (
                                <Box display="flex" alignItems="center" mb={1}>
                                    <Typography minWidth={120}>Created</Typography>
                                    <Typography sx={{ color: '#00bcd4' }}>
                                        {new Date(selectedServer.createdAt).toLocaleString()}
                                    </Typography>
                                </Box>
                            )}
                            {selectedServer.updatedAt && (
                                <Box display="flex" alignItems="center" mb={1}>
                                    <Typography minWidth={120}>Updated</Typography>
                                    <Typography sx={{ color: '#00bcd4' }}>
                                        {new Date(selectedServer.updatedAt).toLocaleString()}
                                    </Typography>
                                </Box>
                            )}
                        </CardContent>
                    </Card>
                </>
            )}
        </Box>
    );
};

export default McpServersGrid;
