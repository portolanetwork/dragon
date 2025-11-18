import * as React from 'react';
import {useEffect, useState} from 'react';
import {DataGrid, GridColDef} from '@mui/x-data-grid';
import {Box, Chip, Divider, Typography, Card, CardContent, Tabs, Tab} from '@mui/material';
import {useAuthState} from 'react-firebase-hooks/auth';
//import {firebaseAuth} from '../../firebase/firebaseConfig';
import { auth } from "../../firebase";

import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import CreateExport from '../components/CreateExport';
import CreateTunnel from "./CreateTunnel";
import CreateTunnelFromExport from "./CreateTunnelFromExport";
import ExportsGrid from './ExportsGrid';
import TunnelsGrid from './TunnelsGrid';
import { SyntheticEvent } from 'react';
import { Button } from '@mui/material';

import { GridRowParams } from '@mui/x-data-grid';
import { TunnelRow } from './types/TunnelRow';
import { EndpointRow } from './types/EndpointRow';

import { ExportedAssetRow } from './types/ExportedAsssetRow';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked';
import CheckBoxIcon from '@mui/icons-material/CheckBox';
import CheckBoxOutlineBlankIcon from '@mui/icons-material/CheckBoxOutlineBlank';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import {useNavigate} from "react-router";
import { Tooltip } from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import UUIDDisplay from './UUIDDisplay';
import CreateTunnelDialog from "./CreateTunnelDialog";
import SyncIcon from '@mui/icons-material/Sync';
import CreateSftpDialog from "./CreateSftpDialog";
import StyledDataGrid from "./StyledDataGrid";
import CreateSshDialog from "./CreateSshDialog";

import AppleIcon from '@mui/icons-material/Apple';
import {useEndpoints} from "./hooks/useEndpoints";
import {Subscript} from "@mui/icons-material";



const EndpointGrid = () => {
    const [user, loading, error] = useAuthState(auth);
    const [exportedAssetRows, setExportedAssetRows] = useState<ExportedAssetRow[]>([]);
    const [selectedEndpoint, setSelectedEndpoint] = useState<EndpointRow | null>(null);
    const [tunnelRows, setTunnelRows] = useState<TunnelRow[]>([]);
    const [tabIndex, setTabIndex] = useState(0);
    const { endpointRows, endpointIdToHostnameMap, fetchEndpoints } = useEndpoints(user);

    const handleQuickStartClick = () => {
        const currentUrl = window.location.href;
        const quickStartUrl = currentUrl.replace('console.', 'docs.') + 'docs/quick-start';
        //window.location.href = "https://docs.staging.portolanetwork.com/docs/quick-start";
        window.location.href = quickStartUrl;
    };

    if (loading) {
        return <div>Loading...</div>;
    }

    if (!user) {
        console.error("User not logged in");
        return;
    }

    useEffect(() => {
        fetchEndpoints();
    }, [fetchEndpoints]);


    const truncateUUID = (uuid: string) => `${uuid.substring(0, 8)}...`;

    // Define a function to copy the UUID to the clipboard
    const copyToClipboard = (uuid: string) => {
        navigator.clipboard.writeText(uuid);
    };

    const columns: GridColDef[] = [
        {
            field: 'selected',
            headerName: '',
            width: 50,
            renderCell: (params) => (
                params.row.id === selectedEndpoint?.id ? <CheckCircleOutlineIcon color="primary" /> : <RadioButtonUncheckedIcon />
            ),
        },
        {
            field: 'id',
            headerName: 'Endpoint ID',
            width: 200, // Adjust the width as needed
            renderCell: (params) => <UUIDDisplay uuid={params.value} />,

        },
        { field: 'hostname', headerName: 'Hostname', width: 250 },
        { field: 'agent_version', headerName: 'Agent Version', width: 120 },
        { field: 'status', headerName: 'Status', width: 120, renderCell: (params) => (
                <Chip
                    label={params.row.isOnline ? "Online" : "Offline"}
                    color={params.row.isOnline ? "primary" : "default"}
                />
            )},
        { field: 'lastOnline', headerName: 'Last Online', width: 150 },
        {
            field: 'actions',
            headerName: 'Actions',
            flex: 1,
            renderCell: (params) => (
                <Chip
                    label={params.row.isCaffeinated ? "DeCaffeinate" : "Caffeinate"}
                    color={params.row.isCaffeinated ? "primary" : "default"}
                    onClick={(event) => {
                        event.stopPropagation();
                        handleChipClick(params.row.id, params.row.isCaffeinated);
                    }}
                />
            ),
        },
    ];


    const handleChipClick = async (id: string, isCaffeinated: boolean) => {
        await SpyderProxy.getInstance()
            .caffeinateEndpoint(user, id, isCaffeinated)
            .then(() => {
                console.log(`Caffeinated endpoint ${id}`);
            }).catch((error) => {
                console.log(`Error caffeinating endpoint ${id}: ${error.message}`);
            });

        await fetchEndpoints();
    };


    const getExportsForEndpoint = async (endpointId: string) => {
        await SpyderProxy.getInstance()
            .getExportsForEndpoint(user, endpointId)
            .then((exportList) => {
                console.log("Exports for endpoint: ", exportList);
                setExportedAssetRows(exportList);
            }).catch((error) => {
                console.log(`Error fetching exports for endpoint ${endpointId}: ${error.message}`);
            });
    };

    const getTunnelsForEndpoint = async (endpointId: string) => {
        console.log("------------- Getting TUNNELS for endpoint: ", endpointId);

        await SpyderProxy.getInstance()
            .getTunnelsForEndpoint(user, endpointId)
            .then((tunnelList) => {

                return tunnelList.map((tunnel) => {
                    console.log("-------------- Tunnel: ", tunnel.left.endpointId);
                    console.log("-------------- Tunnel type: ", tunnel.connectionType);

                    console.log("- Getting Endpoint ID to Hostname Map: ", endpointIdToHostnameMap);

                    const leftHostnameWithState = endpointIdToHostnameMap.get(tunnel.left.endpointId) || ["Unknown", false];
                    const rightHostnameWithState = endpointIdToHostnameMap.get(tunnel.right.endpointId) || ["Unknown", false];

                    tunnel.left.hostname = leftHostnameWithState ? leftHostnameWithState[0] : "Unknown";
                    tunnel.left.endpointIsOnline = leftHostnameWithState ? leftHostnameWithState[1] : false;

                    tunnel.right.hostname = rightHostnameWithState ? rightHostnameWithState[0] : "Unknown";
                    tunnel.right.endpointIsOnline = rightHostnameWithState ? rightHostnameWithState[1] : false;

                    // Tunnel is online if both left and right are online
                    tunnel.isActive = leftHostnameWithState ? leftHostnameWithState[1] : false && rightHostnameWithState ? rightHostnameWithState[1] : false;

                    console.log("Tunnel is online: ", tunnel.isActive);

                   return tunnel;
                });

                return tunnelList;
            })
            .then((tunnelList) => {
                console.log("Tunnels for endpoint: ", tunnelList);
                setTunnelRows(tunnelList);
            })
            .catch((error) => {
                console.log(`Error fetching tunnels for endpoint ${endpointId}: ${error.message}`);
            });
    };

    const handleRowClick = async (params: GridRowParams) => {
        setSelectedEndpoint(params.row);
    };

    const handleTabChange = (event: SyntheticEvent, newValue: number) => {
        setTabIndex(newValue);
    };

    const refreshAll = async () => {
        //await getAllEndpoints();
        await fetchEndpoints();
    }

    const handleDialogClose = async () => {
        console.log("Dialog closed");
        //await getAllEndpoints();
        await fetchEndpoints();
    }

    useEffect(() => {
        const fetchEndpointAssets = async (endpointId: string) => {
            console.log("Fetching assets for endpoint: ", endpointId);
            await getExportsForEndpoint(endpointId);
            await getTunnelsForEndpoint(endpointId);
        };

        if (selectedEndpoint) fetchEndpointAssets(selectedEndpoint.id);
    }, [selectedEndpoint]);


    useEffect(() => {
        const fetchTunnels = async (endpointId: string) => {
            await getExportsForEndpoint(endpointId);
            await getTunnelsForEndpoint(endpointId);
        };

        if (selectedEndpoint) fetchTunnels(selectedEndpoint.id);
    }, [endpointRows]);

    const handleDownloadClick = async () => {
        let appCastUrl = `https://portolanetwork.github.io/portola-appcast/appcast.xml`;

        const currentDomain = window.location.hostname;
        console.log("Current Domain:", currentDomain);

        if (currentDomain === "portola.app") {
            appCastUrl = `https://portolanetwork.github.io/portola-appcast/appcast.xml`;
        } else if (currentDomain === "staging-app.portolanetwork.com" || currentDomain === "localhost") {
            appCastUrl = `https://portolanetwork.github.io/portola-staging-appcast/appcast.xml`;
        }

        console.log("AppCast URL:", appCastUrl);

        try {
            const response = await fetch(appCastUrl);
            if (!response.ok) throw new Error("Failed to fetch appcast.xml");

            const xmlText = await response.text();
            const parser = new DOMParser();
            const xmlDoc = parser.parseFromString(xmlText, "application/xml");

            const enclosure = xmlDoc.querySelector("enclosure");
            if (enclosure) {
                const fileUrl = enclosure.getAttribute("url");
                if (fileUrl) {
                    window.location.href = fileUrl; // Trigger the download
                    console.log("Download triggered for:", fileUrl);
                }
            }
        } catch (error) {
            console.error("Error fetching or parsing appcast.xml:", error);
        }
    };

    return (
        <Box sx={{width: '100%', maxWidth: {sm: '100%', md: '1700px'}}}>
            <Box display="flex" justifyContent="left" alignItems="center">
                { /* PIVOT
                <CreateExport endpointRows={endpointRows} userEmail={user?.email || ''} onDialogClose={handleDialogClose} />
                <Divider orientation="vertical" variant="middle" flexItem/>
                <CreateTunnelDialog endpointRows={endpointRows} tunnelRows={tunnelRows} onDialogClose={handleDialogClose} />
                <Divider orientation="vertical" variant="middle" flexItem/>
                <CreateSshDialog endpointRows={endpointRows} tunnelRows={tunnelRows} onDialogClose={handleDialogClose} />
                <Divider orientation="vertical" variant="middle" flexItem/>
                <CreateSftpDialog endpointRows={endpointRows} tunnelRows={tunnelRows} onDialogClose={handleDialogClose} />
                <Divider orientation="vertical" variant="middle" flexItem/>
                */ }
                <CreateTunnelDialog endpointRows={endpointRows} tunnelRows={tunnelRows} onDialogClose={handleDialogClose} />
                <Divider orientation="vertical" variant="middle" flexItem/>
                <CreateSshDialog endpointRows={endpointRows} tunnelRows={tunnelRows} onDialogClose={handleDialogClose} />
                <Divider orientation="vertical" variant="middle" flexItem/>
                <CreateSftpDialog endpointRows={endpointRows} tunnelRows={tunnelRows} onDialogClose={handleDialogClose} />
                <Divider orientation="vertical" variant="middle" flexItem/>

                <Button variant="text" color="primary" onClick={refreshAll} startIcon={<SyncIcon/>} sx={{padding: '10px 20px', borderRadius: '0'}}>Refresh</Button>
            </Box>

            {endpointRows.length === 0 ? (
                <Box mt={10} textAlign="center">
                    <Typography variant="h6">Get started by provisioning your first endpoint</Typography>
                    <Divider orientation="vertical" variant="middle" flexItem/>
                    <br/>
                    { /*
                    <Button variant="outlined" color="primary" onClick={handleQuickStartClick}>
                        Quick Start
                    </Button>
                    */ }
                    <Button
                        variant="outlined"
                        color="primary"
                        onClick={handleDownloadClick}
                        sx={{ margin: '10px' }}
                        startIcon={<AppleIcon />}
                    >
                        Download for macOS
                    </Button>
                </Box>
            ) : (
                <>
                    <Box mt={4} sx={{'& .MuiTypography-root': {fontWeight: 'bold'}}}>
                        <Card>
                            <CardContent>
                                <Box display="flex" justifyContent="space-between" alignItems="center">
                                    <Typography variant="h5" component="div" gutterBottom>
                                        Endpoints
                                    </Typography>
                                </Box>
                            </CardContent>
                        </Card>
                    </Box>
                    <div style={{/*height: 400, */width: '100%'}}>
                        <StyledDataGrid
                            initialState={{ //Add this prop
                                pagination: { paginationModel: { pageSize: 10 } } // Setting initial pageSize
                            }}
                            rows={endpointRows}
                            columns={columns}
                            //autoPageSize={true}
                            //pageSize={5}
                            pageSizeOptions={[5]}
                            pagination
                            //rowCount={endpointRows.length}
                            onRowClick={handleRowClick}
                        />
                    </div>
                </>
            )}


            <br/>


            {selectedEndpoint != null ? (
                <>
                    <ExpandMoreIcon style={{ fontSize: 100, display: 'block', margin: '20px auto', color: 'blue' }} />

                    <Card sx={{ border: '2px solid', borderColor: 'primary.main' }}>
                        <CardContent>
                            <Typography variant="h5" component="div" gutterBottom>
                                Endpoint details for
                            </Typography>
                            <Box display="flex" alignItems="center">
                                <Typography>Endpoint ID</Typography>
                                <Typography sx={{width: '24px'}}></Typography>
                                <Typography>{selectedEndpoint?.id}</Typography>
                            </Box>
                            <Box display="flex" alignItems="center">
                                <Typography mr={1}>Hostname</Typography>
                                <Typography sx={{width: '24px'}}></Typography>
                                <Typography sx={{ color: '#00bcd4' }}>{selectedEndpoint?.hostname}</Typography>
                            </Box>
                            <Box display="flex" alignItems="center">
                                <Typography mr={1}>Username</Typography>
                                <Typography sx={{width: '24px'}}></Typography>
                                <Typography sx={{ color: '#00bcd4' }}>{selectedEndpoint?.username}</Typography>
                            </Box>
                            <Box display="flex" alignItems="center">
                                <Typography mr={1}>Homedir</Typography>
                                <Typography sx={{width: '24px'}}></Typography>
                                <Typography sx={{ color: '#00bcd4' }}>{selectedEndpoint?.homedir}</Typography>
                            </Box>
                        </CardContent>

                        <br/>

                        <Tabs value={tabIndex} onChange={handleTabChange}>
                            <Tab label="Tunnels"/>
                            <Tab label="Exports"/>
                        </Tabs>
                        {tabIndex === 0 && (
                            <TunnelsGrid
                                tunnelRows={tunnelRows}
                                selectedEndpoint={selectedEndpoint}
                                refreshTunnels={getTunnelsForEndpoint}
                            />
                        )}
                        {tabIndex === 1 && (
                            <ExportsGrid
                                exportedAssetRows={exportedAssetRows}
                                selectedEndpoint={selectedEndpoint}
                                refreshExports={getExportsForEndpoint}
                            />
                        )}

                    </Card>
                </>
            ) : null}


        </Box>
    );
};

export default EndpointGrid;