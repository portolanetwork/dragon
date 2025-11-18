import * as React from 'react';
import {DataGrid, GridColDef, GridRowHeightParams} from '@mui/x-data-grid';
import {
    Box,
    Chip,
    Card,
    CardContent,
    Typography,
    Grid,
    Tooltip,
    Button,
    CircularProgress,
    Divider
} from '@mui/material';
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import {useAuthState} from 'react-firebase-hooks/auth';
import {auth} from '../../firebase';
import {useEffect, useState} from 'react';
import {TunnelRow} from './types/TunnelRow';
import {EndpointRow} from './types/EndpointRow';
import MultipleStopIcon from '@mui/icons-material/MultipleStop';
import CircleIcon from '@mui/icons-material/Circle';
import UUIDDisplay from './UUIDDisplay';
import StyledDataGrid from './StyledDataGrid';
import CreateFileFinderDialog from "./CreateFileFinderDialog";
import FsTunnelsGrid from './FsTunnelsGrid';
import FinderTree from "./FinderTree";
import SyncIcon from "@mui/icons-material/Sync";
import ArrowBackIcon from '@mui/icons-material/ArrowBack';

interface FileFinderProps {
    //refreshAll: () => void;
}

const FileFinder: React.FC<FileFinderProps> = ({}) => {
    const [user] = useAuthState(auth);
    const [endpointRows, setEndpointRows] = useState<EndpointRow[]>([]);
    const [tunnelRows, setTunnelRows] = useState<TunnelRow[]>([]);
    const [endpointIdToHostnameMap, setEndpointIdToHostnameMap] = useState<Map<string, [string, boolean, string]>>(new Map<string, [string, boolean, string]>());
    const [selectedTunnelRow, setSelectedTunnelRow] = useState<TunnelRow | null>(null);

    if (!user) {
        throw new Error("User is not authenticated");
    }

    const refreshAll = async () => {
        await getAllEndpoints();
        await getAllTunnels();

        // Update the selected tunnel row with the latest data
        if (selectedTunnelRow) {
            const updatedTunnel = tunnelRows.find(tunnel => tunnel.tunnelId === selectedTunnelRow.tunnelId);

            console.log("Updated tunnel: ", updatedTunnel);

            setSelectedTunnelRow(updatedTunnel || null);
        }
    }

    useEffect(() => {
        const fetchAllTunnels = async () => {
            await getAllEndpoints();
        };

        fetchAllTunnels();
    }, []);

    useEffect(() => {
        const fetchAllTunnels = async () => {
            await getAllTunnels();
        };

        fetchAllTunnels();
    }, [endpointIdToHostnameMap]);

    const getAllEndpoints = async () => {
        await SpyderProxy.getInstance()
            .getAllEndpoints(user)
            .then((endpointList) => {

                console.log("-----------------------> Endpoint list: ", endpointList);

                let updatedEndpointIdToHostnameMap = new Map<string, [string, boolean, string]>(
                    //console.log("isOnline: ", endpointList.map(endpoint => endpoint.isOnline)),

                    endpointList.map(endpoint => [endpoint.id, [endpoint.hostname, endpoint.isOnline, endpoint.homedir]])
                );

                setEndpointIdToHostnameMap(updatedEndpointIdToHostnameMap);
                setEndpointRows(endpointList);
            }).catch((error) => {
                console.log("Error fetching endpoints: ", error.message);
            });
    };

    const getAllTunnels = async () => {
        await SpyderProxy.getInstance()
            .getAllTunnels(user)
            .then((tunnelList) => {
                return tunnelList
                    .filter(tunnel => tunnel.connectionType === "File Finder")
                    .map((tunnel) => {
                        const leftHostnameWithState = endpointIdToHostnameMap.get(tunnel.left.endpointId) || ["Unknown", false, "Unknown"];
                        const rightHostnameWithState = endpointIdToHostnameMap.get(tunnel.right.endpointId) || ["Unknown", false, "Unknown"];

                        tunnel.left.hostname = leftHostnameWithState[0];
                        tunnel.left.endpointIsOnline = leftHostnameWithState[1];
                        tunnel.left.homedir = leftHostnameWithState[2];

                        tunnel.right.hostname = rightHostnameWithState[0];
                        tunnel.right.endpointIsOnline = rightHostnameWithState[1];
                        tunnel.right.homedir = rightHostnameWithState[2];

                        tunnel.isActive = leftHostnameWithState[1] && rightHostnameWithState[1];

                        return tunnel;
                    });
            })
            .then((tunnelList) => {
                console.log("-----------------------> Tunnel list: ", tunnelList);

                setTunnelRows(tunnelList);
            })
            .catch((error) => {
                console.log(`Error fetching all tunnels : ${error.message}`);
            });
    };


    const handleTunnelDelete = async (tunnelRow: TunnelRow) => {
        console.log("Deleting tunnel: ", tunnelRow);

        await SpyderProxy.getInstance()
            .deleteTunnel(user, tunnelRow.tunnelId)
            .then(() => {
                getAllEndpoints();
            }).catch((error) => {
                console.log(`Error deleting tunnel ${tunnelRow.tunnelId}: ${error.message}`);
            });
    };

    //const truncateUUID = (uuid: string) => `${uuid.substring(0, 8)}...`;


    const handleTunnelSelected = (tunnelRow: TunnelRow) => {
        // Handle logic after endpoints are selected in the dialog
        console.log('Tunnel selected ---  ', tunnelRow);
        // You might want to trigger a refresh here as well, depending on your needs
        // refreshAll();

        setSelectedTunnelRow(tunnelRow);
    };

    const handleDialogClose = async () => {
        console.log("Dialog closed --------------");
        await getAllTunnels();
    }

    return (
        <Box sx={{width: '100%', maxWidth: {sm: '100%', md: '1700px'}}}>
            {selectedTunnelRow != null ?
                <>
                    {
                    <Box display="flex" justifyContent="left" alignItems="center">
                        <Button variant="text" color="primary" onClick={() => setSelectedTunnelRow(null)} startIcon={<ArrowBackIcon />}>Back</Button>
                    </Box>
                     }
                    <FinderTree tunnelRow={selectedTunnelRow} onTunnelRefresh={() => () => { refreshAll(); }} />
                </>
                :
                <>
                    <Box display="flex" justifyContent="left" alignItems="center">
                        <CreateFileFinderDialog endpointRows={endpointRows} onDialogClose={handleDialogClose}/>
                        <Divider orientation="vertical" variant="middle" flexItem/>
                        <Button variant="text" color="primary" onClick={refreshAll} startIcon={<SyncIcon/>} sx={{padding: '10px 20px', borderRadius: '0'}}>Refresh</Button>
                    </Box>
                    <FsTunnelsGrid tunnelRows={tunnelRows} onTunnelSelected={handleTunnelSelected} onTunnelDelete={handleTunnelDelete}/>
                </>

            }
        </Box>
    );
};

export default FileFinder;