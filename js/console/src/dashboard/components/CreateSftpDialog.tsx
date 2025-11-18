import * as React from 'react';
import {useState} from 'react';
import {Dialog, DialogContent, DialogTitle, Tabs, Tab, Box, Button} from '@mui/material';
import CreateTunnel from './CreateTunnel';
import CreateTunnelFromExport from './CreateTunnelFromExport';
import ShareIcon from '@mui/icons-material/Share';
import MultipleStopIcon from '@mui/icons-material/MultipleStop';
import CreateSftp from './CreateSftp';
import {TunnelRow} from "./types/TunnelRow";
import {EndpointRow} from "./types/EndpointRow";



export default function CreateSftpDialog({endpointRows, tunnelRows, onDialogClose}: {
    endpointRows: EndpointRow[],
    tunnelRows: TunnelRow[],
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
                    sx={{padding: '10px 20px', borderRadius: '0'}} startIcon={<ShareIcon/>}
            >
                Create SFTP
            </Button>
            <Dialog open={open} onClose={handleClose} sx={{'& .MuiDialog-paper': {width: '450px'}}}>
                <DialogTitle>Create SFTP</DialogTitle>
                <DialogContent>
                    <CreateSftp endpointRows={endpointRows} onDialogClose={handleClose} />
                </DialogContent>
            </Dialog>
        </Box>
    );
}