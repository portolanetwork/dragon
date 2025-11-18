import * as React from 'react';
import {DataGrid, GridColDef, GridRowHeightParams, GridRowParams} from '@mui/x-data-grid';
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
import CreateFileFinderDialog from "./CreateFileFinderDialog";

interface FsTunnelsGridProps {
    tunnelRows: TunnelRow[]; // Replace TunnelRow with the correct type if needed
    onTunnelSelected: (tunnelRow: TunnelRow) => void; // Callback function type
    onTunnelDelete: (tunnelRow: TunnelRow) => void; // Callback function type
}

const FsTunnelsGrid: React.FC<FsTunnelsGridProps> = ({ tunnelRows, onTunnelSelected, onTunnelDelete }) => {
    const [user] = useAuthState(auth);

    if (!user) {
        throw new Error("User is not authenticated");
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
                        handleDeleteTunnelClick(params.row);
                    }}
                />
            ),
        },
    ];

    const getRowHeight = (params: GridRowHeightParams) => {
        const lineHeight = 28;
        const numLines = 2;
        return lineHeight * numLines;
    };

    const handleTunnelSelected = async (params: GridRowParams) => {
        // Handle logic after endpoints are selected in the dialog
        console.log('Tunnel selected ---  ', params.row);

        onTunnelSelected(params.row);

        // You might want to trigger a refresh here as well, depending on your needs
        // refreshAll();
    };

    const handleDeleteTunnelClick = async (tunnelRow: TunnelRow) => {
        onTunnelDelete(tunnelRow);
    };

    return (
        <Box sx={{ width: '100%', maxWidth: { sm: '100%', md: '1700px' } }}>
            <Box mt={4} sx={{ '& .MuiTypography-root': { fontWeight: 'bold' } }}>
                <Card>
                    <CardContent>
                        <Typography variant="h5" component="div" gutterBottom>
                            File Manager Tunnels
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
                    onRowClick={handleTunnelSelected}
                />
            </div>
        </Box>
    );
};

export default FsTunnelsGrid;