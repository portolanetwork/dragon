import * as React from 'react';
import {useState} from 'react';
import {Dialog, DialogContent, DialogTitle, Tabs, Tab, Box, Button} from '@mui/material';
import CreateTunnel from './CreateTunnel';
import CreateTunnelFromExport from './CreateTunnelFromExport';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import MultipleStopIcon from '@mui/icons-material/MultipleStop';



export default function CreateTunnelDialog({endpointRows, tunnelRows, onDialogClose}: {
    endpointRows: { id: string, hostname: string }[],
    tunnelRows: any[],
    onDialogClose: () => void
}) {
    const [tabIndex, setTabIndex] = useState(0);
    const [open, setOpen] = useState(false);

    const handleTabChange = (event: React.SyntheticEvent, newValue: number) => {
        setTabIndex(newValue);
    };

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
                Create Port-forward
            </Button>
            <Dialog open={open} onClose={handleClose} sx={{'& .MuiDialog-paper': {width: '450px'}}}>
                <DialogTitle>Create Port-forward</DialogTitle>
                <DialogContent>
                    <Tabs value={tabIndex} onChange={handleTabChange}>
                        <Tab label="Direct"/>
                        <Tab label="From Export"/>
                    </Tabs>
                    {tabIndex === 0 &&
                        <CreateTunnel endpointRows={endpointRows} tunnelRows={tunnelRows} onDialogClose={handleClose}/>}
                    {tabIndex === 1 &&
                        <CreateTunnelFromExport endpointRows={endpointRows} onDialogClose={handleClose}/>}
                </DialogContent>
            </Dialog>
        </Box>
    );
}