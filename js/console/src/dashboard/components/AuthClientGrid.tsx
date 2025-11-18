import * as React from 'react';
import { useState, useEffect } from 'react';
import {Box, Button, Card, CardContent, Chip, Divider, Typography} from '@mui/material';
import SyncIcon from '@mui/icons-material/Sync';
import AddIcon from '@mui/icons-material/Add';
import { DataGrid, GridRowHeightParams, GridRenderCellParams } from '@mui/x-data-grid';
import CreateAuthClientDialog from './CreateAuthClientDialog';
import SpyderProxy from '../../spyder_proxy/SpyderProxy';
import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";
import {AuthClientRow} from "./types/AuthClientRow";

import { useEndpoints } from './hooks/useEndpoints';
/*
type AuthClientRow = {
    id: string;
    clientId: string;
    clientSecret: string;
    endpointId: string;
};

 */

const AuthClientGrid: React.FC = () => {
    const [user, loading, error] = useAuthState(auth);
    const [rows, setRows] = useState<AuthClientRow[]>([]);
    const { endpointRows, endpointIdToHostnameMap, fetchEndpoints } = useEndpoints(user);

    useEffect(() => {
        fetchEndpoints();
    }, [fetchEndpoints]);

    if (loading) {
        return <div>Loading...</div>;
    }

    if (!user) {
        console.error("User not logged in");
        return;
    }

    const fetchClients = async () => {
        // Replace with actual fetch logic
        const fetchedClients: AuthClientRow[] = await SpyderProxy.getInstance().getAllClients(user);

        setRows(fetchedClients);
    };

    useEffect(() => {
        fetchClients();
    }, []);

    const handleRefresh = () => {
        fetchClients();
    };

    const handleDeleteClient = async (clientId: string) => {
        // Implement actual delete logic here, e.g.:
        await SpyderProxy.getInstance().deleteClient(user, clientId);

        await fetchClients();
    };

    useEffect(()  => {
        fetchClients();
    }, []);

    const columns = [
        { field: 'clientId', headerName: 'Client ID', width: 300 },
        {
            field: 'endpointId',
            headerName: 'Endpoint ID',
            width: 300,
            renderCell: (params: GridRenderCellParams) => (
                <span>{params.value || '*'}</span>
            ),
        },
        {
            field: 'endpointHostname',
            headerName: 'Endpoint Hostname',
            width: 250,
            renderCell: (params: GridRenderCellParams) => {
                const endpointId = params.row.endpointId;
                if (!endpointId || endpointId === '*') {
                    return (
                        <Box display="flex" alignItems="center" height="100%">
                            <Typography color="warning.main" fontWeight="bold">
                                not bound to specific endpoint
                            </Typography>
                        </Box>
                    );
                }
                const hostname = endpointIdToHostnameMap.get(endpointId)?.[0] || 'Unknown';
                return <span>{hostname}</span>;
            }
        },
        {
            field: 'actions',
            headerName: 'Actions',
            flex: 1,
            renderCell: (params: GridRenderCellParams) => (
                <Chip
                    label="Delete"
                    color="secondary"
                    onClick={(event) => {
                        event.stopPropagation();
                        handleDeleteClient(params.row.clientId);
                    }}
                />
            ),
        },

    ];

    const getRowHeight = (params: GridRowHeightParams) => 56;

    return (
        <Box sx={{width: '100%', maxWidth: {sm: '100%', md: '1700px'}}}>
            <Box display="flex" justifyContent="left" alignItems="center">
                <CreateAuthClientDialog onDialogClose={fetchClients} endpointRows={endpointRows} />
                <Divider orientation="vertical" variant="middle" flexItem/>
                <Button variant="text" color="primary" onClick={handleRefresh} startIcon={<SyncIcon />} sx={{padding: '10px 20px', borderRadius: '0'}}>
                    Refresh
                </Button>
            </Box>
            <Box mt={2}>
                <Card>
                    <CardContent>
                        <Typography variant="h5" component="div" gutterBottom>
                            Auth Clients
                        </Typography>
                    </CardContent>
                </Card>
            </Box>
            <div style={{ height: 500, width: '100%' }}>
                <DataGrid
                    rows={rows}
                    columns={columns}
                    pageSizeOptions={[10]}
                    pagination
                    getRowHeight={getRowHeight}
                />
            </div>
        </Box>
    );
};

export default AuthClientGrid;