import * as React from 'react';
import {useState, useEffect} from 'react';
import {Dialog, DialogContent, DialogTitle, Box, Button, Typography, Chip} from '@mui/material';
import {EndpointRow} from './types/EndpointRow';
import ShareIcon from '@mui/icons-material/Share';
import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import StyledDataGrid from './StyledDataGrid';
import {GridColDef, GridRowParams} from '@mui/x-data-grid';
import CheckCircleOutlineIcon from "@mui/icons-material/CheckCircleOutline";
import RadioButtonUncheckedIcon from "@mui/icons-material/RadioButtonUnchecked";
import UUIDDisplay from "./UUIDDisplay";

interface SelectEndpointDialogProps {
    onEndpointSelected: (endpoint: EndpointRow) => void;
}

export default function SelectEndpointDialog({onEndpointSelected}: SelectEndpointDialogProps) {
    const [user, loading, error] = useAuthState(auth);
    const [open, setOpen] = useState(false);
    const [endpointRows, setEndpointRows] = useState<EndpointRow[]>([]);
    const [selectedEndpoint, setSelectedEndpoint] = useState<EndpointRow | null>(null);

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

    const handleClickOpen = () => {
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
    };

    const handleRowClick = (params: GridRowParams) => {
        const endpoint = params.row as EndpointRow;
        if (endpoint.isOnline) {
            onEndpointSelected(endpoint);
            handleClose();
        }
    };


    const columns: GridColDef[] = [
        {
            field: 'id',
            headerName: 'Endpoint ID',
            width: 150, // Adjust the width as needed
            renderCell: (params) => <UUIDDisplay uuid={params.value}/>,

        },
        {field: 'hostname', headerName: 'Hostname', width: 140},
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
        <Box>
            <Button variant="text" color="primary" onClick={handleClickOpen}
                    sx={{padding: '10px 20px', borderRadius: '0'}} startIcon={<ShareIcon/>}
            >
                Select Endpoint
            </Button>
            <Dialog open={open} onClose={handleClose} sx={{'& .MuiDialog-paper': {width: '450px'}}}>
                <DialogTitle>Select Endpoint</DialogTitle>
                <DialogContent>
                    <div style={{height: 400, width: '100%'}}>
                        <StyledDataGrid
                            initialState={{ //Add this prop
                                pagination: {paginationModel: {pageSize: 5}} // Setting initial pageSize
                            }}

                            rows={endpointRows}
                            columns={columns}
                            //pageSize={5}
                            onRowClick={handleRowClick}
                        />
                    </div>
                </DialogContent>
            </Dialog>
        </Box>
    );
}