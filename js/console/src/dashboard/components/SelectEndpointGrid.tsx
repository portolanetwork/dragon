import * as React from 'react';
import {useState, useEffect} from 'react';
import {Dialog, DialogContent, DialogTitle, Box, Button, Typography, Chip, Radio} from '@mui/material';
import {EndpointRow} from './types/EndpointRow';
import ShareIcon from '@mui/icons-material/Share';
import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import StyledDataGrid from './StyledDataGrid';
import {GridColDef, GridRowParams} from '@mui/x-data-grid';
import UUIDDisplay from "./UUIDDisplay";

interface SelectEndpointDialogProps {
    onEndpointSelected: (endpoint: EndpointRow) => void;
}

export default function SelectEndpointGrid({onEndpointSelected}: SelectEndpointDialogProps) {
    const [user, loading, error] = useAuthState(auth);
    const [endpointRows, setEndpointRows] = useState<EndpointRow[]>([]);

    if (loading) {
        return <div>Loading...</div>;
    }

    if (!user) {
        console.error("User not logged in");
        return;
    }

    const getAllEndpoints = async () => {
        await SpyderProxy.getInstance()
            .getAllEndpoints(user)
            .then((endpointList) => {
                setEndpointRows(endpointList);
            }).catch((error) => {
                console.log("Error fetching endpoints: ", error.message);
            });
    };

    useEffect(() => {
        const fetchAllEndpoints = async () => {
            getAllEndpoints();
        };
        fetchAllEndpoints();
    }, []);

    const handleRowClick = (params: GridRowParams) => {
        const endpoint = params.row as EndpointRow;
        //if (endpoint.isOnline) {
            onEndpointSelected(endpoint);
        //}
    };

    const columns: GridColDef[] = [
        {
            field: 'id',
            headerName: 'Endpoint ID',
            width: 150,
            renderCell: (params) => <UUIDDisplay uuid={params.value}/>,
        },
        {field: 'hostname', headerName: 'Hostname', width: 240},
        {
            field: 'status', headerName: 'Status', width: 100, renderCell: (params) => (
                <Chip
                    label={params.row.isOnline ? "Online" : "Offline"}
                    color={params.row.isOnline ? "primary" : "default"}
                />
            )
        },
    ];

    return (
        <Box sx={{width: '100%', maxWidth: {sm: '100%', md: '1700px'}}}>
            <div style={{height: 400, width: '100%'}}>
                <StyledDataGrid
                    initialState={{pagination: {paginationModel: {pageSize: 5}}}}
                    rows={endpointRows}
                    columns={columns}
                    pageSizeOptions={[5]}
                    //pagination
                    onRowClick={handleRowClick}
                />
            </div>
        </Box>
    );
}