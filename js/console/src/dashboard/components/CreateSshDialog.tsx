import * as React from 'react';
import {useState} from 'react';
import {Dialog, DialogContent, DialogTitle, Tabs, Tab, Box, Button} from '@mui/material';
import CreateTunnel from './CreateTunnel';
import CreateTunnelFromExport from './CreateTunnelFromExport';
import ShareIcon from '@mui/icons-material/Share';
import MultipleStopIcon from '@mui/icons-material/MultipleStop';
import CreateSsh from './CreateSsh';
import { IconPrompt } from '@tabler/icons-react';
import {TunnelRow} from "./types/TunnelRow";



export default function CreateSshDialog({endpointRows, tunnelRows, onDialogClose}: {
    endpointRows: { id: string, hostname: string }[],
    tunnelRows: TunnelRow[],
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
                    sx={{padding: '10px 20px', borderRadius: '0'}} startIcon={<IconPrompt/>}
            >
                Create SSH
            </Button>
            <Dialog open={open} onClose={handleClose} sx={{'& .MuiDialog-paper': {width: '450px'}}}>
                <DialogTitle>Create SSH</DialogTitle>
                <DialogContent>
                    <CreateSsh endpointRows={endpointRows} onDialogClose={handleClose} />
                </DialogContent>
            </Dialog>
        </Box>
    );
}