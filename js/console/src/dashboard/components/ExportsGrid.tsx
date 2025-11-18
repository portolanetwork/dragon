import * as React from 'react';
import { DataGrid, GridColDef } from '@mui/x-data-grid';
import { Box, Chip, Card, CardContent, Typography } from '@mui/material';
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import { useAuthState } from 'react-firebase-hooks/auth';
//import { firebaseAuth } from '../../firebase/firebaseConfig';
import { auth } from "../../firebase";

import { ExportedAssetRow } from './types/ExportedAsssetRow';
import { EndpointRow } from './types/EndpointRow';
import StyledDataGrid from "./StyledDataGrid";


interface ExportsGridProps {
    exportedAssetRows: ExportedAssetRow[];
    selectedEndpoint: EndpointRow;
    refreshExports: (endpointId: string) => Promise<void>;
}

const ExportsGrid: React.FC<ExportsGridProps>  = ({exportedAssetRows, selectedEndpoint, refreshExports }) => {
    const [user] = useAuthState(auth);

    if (!user) {
        throw new Error("User is not authenticated");
    }

    const handleDeleteClick = async (exportId: string) => {
        await SpyderProxy.getInstance()
            .deleteConnectorExport(user, exportId)
            .then(() => {
                console.log(`Deleted export ${exportId}`);
                refreshExports(selectedEndpoint.id);
            }).catch((error) => {
                console.log(`Error deleting export ${exportId}: ${error.message}`);
            });
    };

    const detailColumns: GridColDef[] = [
        { field: 'id', headerName: 'Export ID', width: 320 },
        { field: 'exportedAsset', headerName: 'Exported Asset', width: 300 },
        { field: 'toUser', headerName: 'To User', width: 300 },
        {
            field: 'actions',
            headerName: 'Actions',
            flex: 1,
            renderCell: (params) => (
                <Chip
                    label="Delete"
                    color="secondary"
                    onClick={(event) => {
                        event.stopPropagation();
                        handleDeleteClick(params.row.id);
                    }}
                />
            ),
        },
    ];

    return (
        <Box mt={4}>
            <Card>
                <CardContent>
                    <Typography variant="h5" component="div" gutterBottom>
                        Exports - Mine
                    </Typography>
                </CardContent>
            </Card>
            <div style={{height: 500, width: '100%'}}>
                <StyledDataGrid
                    initialState={{ //Add this prop
                        pagination: { paginationModel: { pageSize: 5 } } // Setting initial pageSize
                    }}
                    rows={exportedAssetRows}
                    columns={detailColumns}
                    //pageSize={5}
                    //pageSizeOptions={[5, 10, 20]}
                    pagination
                />
            </div>
        </Box>
    );
};

export default ExportsGrid;