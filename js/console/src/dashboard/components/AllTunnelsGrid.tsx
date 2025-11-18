import * as React from 'react';
import { DataGrid, GridColDef, GridRowHeightParams } from '@mui/x-data-grid';
import { Box, Chip, Card, CardContent, Typography, Grid, Tooltip, Button, CircularProgress } from '@mui/material';
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import { useAuthState } from 'react-firebase-hooks/auth';
import { auth } from '../../firebase';
import { useEffect, useState } from 'react';
import { TunnelRow } from './types/TunnelRow';
import { EndpointRow } from './types/EndpointRow';
import MultipleStopIcon from '@mui/icons-material/MultipleStop';
import CircleIcon from '@mui/icons-material/Circle';
import UUIDDisplay from './UUIDDisplay';
import StyledDataGrid from './StyledDataGrid';
import SyncIcon from "@mui/icons-material/Sync";

const AllTunnelsGrid: React.FC = () => {
    const [user] = useAuthState(auth);
    const [endpointRows, setEndpointRows] = useState<EndpointRow[]>([]);
    const [tunnelRows, setTunnelRows] = useState<TunnelRow[]>([]);
    const [endpointIdToHostnameMap, setEndpointIdToHostnameMap] = useState<Map<string, [string, boolean]>>(new Map<string, [string, boolean]>());

    if (!user) {
        throw new Error("User is not authenticated");
    }

    useEffect(() => {
        const fetchAllTunnels = async () => {
            await getAllEndpoints();
        };

        fetchAllTunnels();
    }, []);

    useEffect(() => {
        const fetchAllTunnels = async () => {
            await getAllTunnels();
        };

        fetchAllTunnels();
    }, [endpointIdToHostnameMap]);

    const getAllEndpoints = async () => {
        await SpyderProxy.getInstance()
            .getAllEndpoints(user)
            .then((endpointList) => {
                let updatedEndpointIdToHostnameMap = new Map<string, [string, boolean]>(
                    endpointList.map(endpoint => [endpoint.id, [endpoint.hostname, endpoint.isOnline]])
                );

                setEndpointIdToHostnameMap(updatedEndpointIdToHostnameMap);
                setEndpointRows(endpointList);
            }).catch((error) => {
                console.log("Error fetching endpoints: ", error.message);
            });
    };

    const getAllTunnels = async () => {
        await SpyderProxy.getInstance()
            .getAllTunnels(user)
            .then((tunnelList) => {
                return tunnelList.map((tunnel) => {
                    const leftHostnameWithState = endpointIdToHostnameMap.get(tunnel.left.endpointId) || ["Unknown", false];
                    const rightHostnameWithState = endpointIdToHostnameMap.get(tunnel.right.endpointId) || ["Unknown", false];

                    tunnel.left.hostname = leftHostnameWithState[0];
                    tunnel.left.endpointIsOnline = leftHostnameWithState[1];

                    tunnel.right.hostname = rightHostnameWithState[0];
                    tunnel.right.endpointIsOnline = rightHostnameWithState[1];

                    tunnel.isActive = leftHostnameWithState[1] && rightHostnameWithState[1];

                    return tunnel;
                });
            })
            .then((tunnelList) => {
                setTunnelRows(tunnelList);
            })
            .catch((error) => {
                console.log(`Error fetching all tunnels : ${error.message}`);
            });
    };

    const handleDeleteTunnelClick = async (tunnelId: string) => {
        await SpyderProxy.getInstance()
            .deleteTunnel(user, tunnelId)
            .then(() => {
                getAllEndpoints();
            }).catch((error) => {
                console.log(`Error deleting tunnel ${tunnelId}: ${error.message}`);
            });
    };

    const handleDialogClose = async () => {
        await getAllEndpoints();
    }

    const refreshAll = async () => {
        await getAllEndpoints();
    }

    const truncateUUID = (uuid: string) => `${uuid.substring(0, 8)}...`;

    const tunnelColumns: GridColDef[] = [
        { field: 'tunnelId', headerName: 'Tunnel ID', width: 150,
            renderCell: (params) => <UUIDDisplay uuid={params.value} />,
        },
        {
            field: 'connectionType', headerName: 'Type', width: 120, renderCell: (params) => {
                let label = params.row.connectionType;
                return <Chip label={label} />;
            },
        },
        {
            field: 'source', headerName: 'Left', width: 320, renderCell: (params) => (
                <Grid container spacing={0.5}>
                    <Grid item xs={4}>
                        <Typography variant="subtitle1" sx={{ fontWeight: 'bold', fontSize: '0.875rem', textAlign: 'left' }}>
                            Endpoint ID:
                        </Typography>
                    </Grid>
                    <Grid item xs={8}>
                        <Tooltip title={params.row.left.endpointId}>
                            <Typography variant="body1" sx={{ fontSize: '0.875rem', textAlign: 'left' }}>
                                {truncateUUID(params.row.left.endpointId)}
                            </Typography>
                        </Tooltip>
                    </Grid>

                    <Grid item xs={4}>
                        <Typography variant="subtitle1" sx={{ fontWeight: 'bold', fontSize: '0.875rem', textAlign: 'left' }}>
                            Hostname:
                        </Typography>
                    </Grid>
                    <Grid item xs={8}>
                        <Box display="flex" alignItems="center">
                            <Typography variant="body1" sx={{ fontSize: '0.875rem', color: '#00bcd4', textAlign: 'left' }}>
                                {params.row.left.hostname}
                            </Typography>
                            <CircleIcon
                                sx={{
                                    fontSize: 15,
                                    color: params.row.left.endpointIsOnline ? '#00FF00' : '#808080',
                                    ml: 1,
                                }}
                            />
                        </Box>
                    </Grid>

                    <Grid item xs={4}>
                        <Typography variant="subtitle1" sx={{ fontWeight: 'bold', fontSize: '0.875rem', textAlign: 'left' }}>
                            Asset:
                        </Typography>
                    </Grid>
                    <Grid item xs={8}>
                        <Typography variant="body1" sx={{ fontSize: '0.875rem', textAlign: 'left' }}>
                            {params.row.left.exportedAsset}
                        </Typography>
                    </Grid>
                </Grid>
            ),
        },
        {
            field: 'sta', headerName: '', width: 50, renderCell: (params) => (
                <MultipleStopIcon style={{ fontSize: 40, color: params.row.isActive ? 'green' : 'gray' }} />
            ),
        },
        {
            field: 'destination', headerName: 'Right', width: 320, renderCell: (params) => (
                <Grid container spacing={0.5}>
                    <Grid item xs={4}>
                        <Typography variant="subtitle1" sx={{ fontWeight: 'bold', fontSize: '0.875rem', textAlign: 'left' }}>
                            Endpoint ID:
                        </Typography>
                    </Grid>
                    <Grid item xs={8}>
                        <Tooltip title={params.row.right.endpointId}>
                            <Typography variant="body1" sx={{ fontSize: '0.875rem', textAlign: 'left' }}>
                                {truncateUUID(params.row.right.endpointId)}
                            </Typography>
                        </Tooltip>
                    </Grid>

                    <Grid item xs={4}>
                        <Typography variant="subtitle1" sx={{ fontWeight: 'bold', fontSize: '0.875rem', textAlign: 'left' }}>
                            Hostname:
                        </Typography>
                    </Grid>
                    <Grid item xs={8}>
                        <Box display="flex" alignItems="center">
                            <Typography variant="body1" sx={{ fontSize: '0.875rem', color: '#00bcd4', textAlign: 'left' }}>
                                {params.row.right.hostname}
                            </Typography>
                            <CircleIcon
                                sx={{
                                    fontSize: 15,
                                    color: params.row.right.endpointIsOnline ? '#00FF00' : '#808080',
                                    ml: 1,
                                }}
                            />
                        </Box>
                    </Grid>

                    <Grid item xs={4}>
                        <Typography variant="subtitle1" sx={{ fontWeight: 'bold', fontSize: '0.875rem', textAlign: 'left' }}>
                            Forwarded to:
                        </Typography>
                    </Grid>
                    <Grid item xs={8}>
                        <Typography variant="body1" sx={{ fontSize: '0.875rem', textAlign: 'left' }}>
                            {params.row.right.exportedAsset}
                        </Typography>
                    </Grid>
                </Grid>
            ),
        },
        {
            field: 'status', headerName: 'Status', width: 120, renderCell: (params) => (
                <Chip
                    label={params.row.isActive ? "Active" : "InActive"}
                    color={params.row.isActive ? "primary" : "default"}
                />
            ),
        },
        {
            field: 'actions',
            headerName: 'Actions',
            flex: 1,
            renderCell: (params) => (
                <Chip
                    label="Delete"
                    color="secondary"
                    onClick={(event) => {
                        event.stopPropagation();
                        handleDeleteTunnelClick(params.row.tunnelId);
                    }}
                />
            ),
        },
    ];

    const getRowHeight = (params: GridRowHeightParams) => {
        const lineHeight = 28;
        const numLines = 3;
        return lineHeight * numLines;
    };

    return (
        <Box sx={{ width: '100%', maxWidth: { sm: '100%', md: '1700px' } }}>
            <Box display="flex" justifyContent="left" alignItems="center">
                <Button variant="text" color="primary" onClick={refreshAll} startIcon={<SyncIcon/>} sx={{padding: '10px 20px', borderRadius: '0'}}>Refresh</Button>
            </Box>
            <Box mt={4} sx={{ '& .MuiTypography-root': { fontWeight: 'bold' } }}>
                <Card>
                    <CardContent>
                        <Typography variant="h5" component="div" gutterBottom>
                            Tunnels
                        </Typography>
                    </CardContent>
                </Card>
            </Box>
            <div style={{ height: 950, width: '100%' }}>
                <StyledDataGrid
                    initialState={{
                        pagination: { paginationModel: { pageSize: 10 } }
                    }}
                    rows={tunnelRows}
                    columns={tunnelColumns}
                    pageSizeOptions={[10]}
                    pagination
                    getRowHeight={getRowHeight}
                />
            </div>
        </Box>
    );
};

export default AllTunnelsGrid;