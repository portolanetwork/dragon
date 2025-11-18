import * as React from 'react';
import {useState} from 'react';
import {Dialog, DialogContent, DialogTitle, Tabs, Tab, Box, Button} from '@mui/material';
import CreateTunnel from './CreateTunnel';
import CreateTunnelFromExport from './CreateTunnelFromExport';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import MultipleStopIcon from '@mui/icons-material/MultipleStop';
import {EndpointRow} from "./types/EndpointRow";
import CreateFileFinder from './CreateFileFinder';



export default function CreateFileFinderDialog({endpointRows, onDialogClose}: {
    endpointRows: EndpointRow[],
    onDialogClose: () => void
}) {
    const [open, setOpen] = useState(false);

    const handleClickOpen = () => {
        setOpen(true);
    };

    const handleClose = () => {
        setOpen(false);
        onDialogClose();
    };

    return (
        <Box>
            <Button variant="text" color="primary" onClick={handleClickOpen}
                    sx={{padding: '10px 20px', borderRadius: '0'}} startIcon={<SwapHorizIcon/>}
            >
                Create File Manager
            </Button>
            <Dialog open={open} onClose={handleClose} sx={{'& .MuiDialog-paper': {width: '900px'}}}>
                <DialogTitle>Create File Manager</DialogTitle>
                <DialogContent>
                    <CreateFileFinder endpointRows={endpointRows} onDialogClose={handleClose}/>
                </DialogContent>
            </Dialog>
        </Box>
    );
}