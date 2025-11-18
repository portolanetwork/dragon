import * as React from 'react';
import {useEffect, useState} from 'react';
import { Box, Button, Card, CardContent, Chip, Divider, Grid, Typography } from '@mui/material';
import { DataGrid, GridRowHeightParams } from '@mui/x-data-grid';
import CreateExport from './CreateExport';
import CreateTunnel from './CreateTunnel';
import CreateTunnelFromExport from './CreateTunnelFromExport';
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import {useAuthState} from "react-firebase-hooks/auth";
import { auth } from "../../firebase";
import {ExportedAssetRow} from "./types/ExportedAsssetRow";
import {EndpointRow} from "./types/EndpointRow";
import Stack from "@mui/material/Stack";
import Avatar from "@mui/material/Avatar";
import { GridRenderCellParams } from '@mui/x-data-grid';
import OptionsMenu from "./OptionsMenu";
import UUIDDisplay from "./UUIDDisplay";
import StyledDataGrid from "./StyledDataGrid";
import SyncIcon from "@mui/icons-material/Sync";
import Alert from '@mui/material/Alert';


const AllExportsGrid: React.FC = () => {
    const [user, loading, error] = useAuthState(auth);
    const [endpointRows, setEndpointRows] = useState<EndpointRow[]>([]);
    const [exportRows, setExportRows] = React.useState<ExportedAssetRow[]>([]);

    if (loading) {
        return <div>Loading...</div>;
    }

    if (!user) {
        console.error("User not logged in");
        return;
    }

    useEffect(() => {
        // Fetch exports
        const fetchExports = async () => {
            // Fetch exports

            const exports = await SpyderProxy.getInstance().getAllConnectorExportsToMe(user);

            console.log("------------------------- exports: ", exports);


            setExportRows(exports);
        };

        fetchExports();
    }, []);



    const handleDialogClose = () => {
        // Handle dialog close
    };

    const refreshAll = () => {
        // Refresh data
    };

    const handleDeleteExportClick = (exportId: string) => {
        // Handle delete export
    };

    const exportColumns = [
        {
            field: 'id', headerName: 'Export ID', width: 150, renderCell: (params: GridRenderCellParams) => <UUIDDisplay uuid={params.value} />,
        },
        {
            field: 'exportedAsset', headerName: 'Exported Asset', width: 220,
        },
        {
            field: 'fromUser', headerName: 'From User', width: 300,
            renderCell: (params: GridRenderCellParams) => {
                //const { displayName, email } = params.row.fromUserName;
                return (
                    <Box sx={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 1, // Use theme spacing (e.g., 8px if theme.spacing(1) = 8px)
                        // py: 0.5,  // Optional padding (using theme spacing)
                    }}>
                        <Avatar
                            sizes="small"
                            alt={params.row.fromUserName}
                            src={params.row.fromUserPicture}
                            sx={{ width: 36, height: 36 }}
                        />
                        <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', padding: '10px' }}>
                            <Stack spacing={0.5}> {/* Adjust spacing as needed (e.g., 0.5, 1, 2) */}
                                <Typography variant="body2" sx={{ fontWeight: 500, lineHeight: '16px' }}>
                                    {params.row.fromUserName}
                                </Typography>
                                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                                    {params.row.fromUserEmail}
                                </Typography>
                            </Stack>
                        </Box>
                    </Box>
                );
            },
        },
    ];

    const getRowHeight = (params: GridRowHeightParams) => {
        const lineHeight = 28;
        const numLines = 2;
        return lineHeight * numLines;
    };

    return (
        <Box sx={{width: '100%', maxWidth: {sm: '100%', md: '1700px'}}}>
            {!user.emailVerified && (
                <Alert severity="info" sx={{ marginBottom: 2 }}>
                    Your email <strong>{user.email}</strong> is not verified. If you logged in with GitHub, make sure that your GitHub account email is verified and re-login. Alternatively, use Google or Email link-based login.
                </Alert>
            )}
            {
            <Box display="flex" justifyContent="left" alignItems="center">
                { /*<CreateExport endpointRows={endpointRows} userEmail={user?.email || ''} onDialogClose={handleDialogClose} />
                <Divider orientation="vertical" variant="middle" flexItem/> */ }
                <Button variant="text" color="primary" onClick={refreshAll} startIcon={<SyncIcon/>} sx={{padding: '10px 20px', borderRadius: '0'}}>Refresh</Button>
            </Box>
            }
            <Box mt={4} sx={{'& .MuiTypography-root': {fontWeight: 'bold'}}}>
                <Card>
                    <CardContent>
                        <Typography variant="h5" component="div" gutterBottom>
                            Exports - Theirs
                        </Typography>
                    </CardContent>
                </Card>
            </Box>
            <div style={{height: 800, width: '100%'}}>
                <StyledDataGrid
                    initialState={{ //Add this prop
                        pagination: { paginationModel: { pageSize: 10 } } // Setting initial pageSize
                    }}
                    rows={exportRows}
                    columns={exportColumns}
                    //pageSize={10}
                    pageSizeOptions={[10]}
                    pagination
                    getRowHeight={getRowHeight}
                />
            </div>
        </Box>
    );
};

export default AllExportsGrid;