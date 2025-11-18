import * as React from 'react';
import { DataGrid, GridColDef } from '@mui/x-data-grid';
import { Box, Chip, Card, CardContent, Typography, Grid } from '@mui/material';
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import { useAuthState } from 'react-firebase-hooks/auth';
//import { firebaseAuth } from '../../firebase/firebaseConfig';
import { auth } from "../../firebase";

import { GridRowHeightParams } from '@mui/x-data-grid';

import { TunnelRow } from './types/TunnelRow';
import { EndpointRow } from './types/EndpointRow';

import MultipleStopIcon from '@mui/icons-material/MultipleStop';
import CircleIcon from '@mui/icons-material/Circle';

import { Tooltip, Button } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import UUIDDisplay from './UUIDDisplay';
import { useTheme } from '@mui/material/styles';
import StyledDataGrid from "./StyledDataGrid";


interface TunnelsGridProps {
    tunnelRows: TunnelRow[];
    selectedEndpoint: EndpointRow;
    refreshTunnels: (endpointId: string) => Promise<void>;
}

const TunnelsGrid: React.FC<TunnelsGridProps>  = ({ tunnelRows, selectedEndpoint, refreshTunnels }) => {
    const [user] = useAuthState(auth);
    const theme = useTheme();

    if (!user) {
        throw new Error("User is not authenticated");
    }

    const handleDeleteTunnelClick = async (tunnelId: string) => {
        await SpyderProxy.getInstance()
            .deleteTunnel(user, tunnelId)
            .then(() => {
                console.log(`Deleted tunnel ${tunnelId}`);
                refreshTunnels(selectedEndpoint.id);
            }).catch((error) => {
                console.log(`Error deleting tunnel ${tunnelId}: ${error.message}`);
            });
    };

    // Define a function to truncate the UUID
    const truncateUUID = (uuid: string) => `${uuid.substring(0, 8)}...`;

    // Define a function to copy the UUID to the clipboard
    const copyToClipboard = (uuid: string) => {
        navigator.clipboard.writeText(uuid);
    };


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
                <Grid container spacing={0.5}> {/* Reduced spacing even further */}
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
                </Grid>            ),
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
        const lineHeight = 30;
        const numLines = 3;
        return lineHeight * numLines;
    };

    return (
        <Box mt={4}>
            <Card>
                <CardContent>
                    <Typography variant="h5" component="div" gutterBottom>
                        Tunnels
                    </Typography>
                </CardContent>
            </Card>
            <div style={{width: '100%'}}>
                <StyledDataGrid
                    initialState={{ //Add this prop
                        pagination: { paginationModel: { pageSize: 10 } } // Setting initial pageSize
                    }}
                    rows={tunnelRows}
                    columns={tunnelColumns}
                    //pageSizeOptions={[5]}
                    //pageSize={5}
                    //autoPageSize={true}
                    //pageSizeOptions={[5, 10, 20]}
                    pagination
                    getRowHeight={getRowHeight}
                />
            </div>
        </Box>
    );
};

export default TunnelsGrid;