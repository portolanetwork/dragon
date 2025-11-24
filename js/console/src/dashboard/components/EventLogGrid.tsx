import * as React from 'react';
import { useEffect, useState } from 'react';
import { GridColDef, GridRowParams } from '@mui/x-data-grid';
import { Box, Chip, Divider, Typography, Card, CardContent, Button, Tooltip } from '@mui/material';
import { useAuth0 } from '@auth0/auth0-react';
import DragonProxy, { EventLogRow } from "../../dragon_proxy/DragonProxy";
import StyledDataGrid from "./StyledDataGrid";
import UUIDDisplay from './UUIDDisplay';
import SyncIcon from '@mui/icons-material/Sync';
import CodeIcon from '@mui/icons-material/Code';

interface EventLogGridProps {
    refreshTrigger?: number;
}

const EventLogGrid = ({ refreshTrigger }: EventLogGridProps) => {
    const { user, isLoading, isAuthenticated, getAccessTokenSilently } = useAuth0();
    const [eventLogRows, setEventLogRows] = useState<EventLogRow[]>([]);
    const [nextCursor, setNextCursor] = useState<string>("");

    if (isLoading) {
        return <div>Loading...</div>;
    }

    if (!isAuthenticated || !user) {
        console.error("User not logged in");
        return <div>User not logged in</div>;
    }












    useEffect(() => {
        const fetchEventLogs = async () => {
            try {
                const accessToken = await getAccessTokenSilently();
                const result = await DragonProxy.getInstance().getEventLogWithToken(user.sub!, accessToken);
                setEventLogRows(result.events);
                setNextCursor(result.nextCursor);
            } catch (error: any) {
                console.error("Error fetching event logs: ", error.message);
            }
        };
        fetchEventLogs();
    }, [user, refreshTrigger, getAccessTokenSilently]);

    const getEventTypeColor = (eventType: string): "default" | "primary" | "secondary" | "success" | "error" | "info" | "warning" => {
        if (eventType.includes("ERROR") || eventType.includes("FAILED")) return "error";
        if (eventType.includes("SUCCESS") || eventType.includes("COMPLETED")) return "success";
        if (eventType.includes("STARTED") || eventType.includes("INITIATED")) return "info";
        if (eventType.includes("WARNING")) return "warning";
        return "primary";
    };

    const columns: GridColDef[] = [
        {
            field: 'uuid',
            headerName: 'UUID',
            width: 200,
            renderCell: (params) => <UUIDDisplay uuid={params.value} />,
        },
        {
            field: 'eventType',
            headerName: 'Event Type',
            width: 200,
            renderCell: (params) => (
                <Chip
                    label={params.value}
                    color={getEventTypeColor(params.value)}
                    size="small"
                />
            ),
        },
        {
            field: 'description',
            headerName: 'Description',
            width: 350
        },
        {
            field: 'metadata',
            headerName: 'Metadata',
            flex: 1,
            minWidth: 350,
            renderCell: (params) => {
                if (!params.value) return <Typography variant="body2" color="text.secondary">-</Typography>;

                try {
                    const jsonString = JSON.stringify(params.value, null, 2);
                    const compactString = JSON.stringify(params.value);
                    const isLarge = compactString.length > 50;

                    return (
                        <Tooltip
                            title={
                                <Box
                                    component="pre"
                                    sx={{
                                        margin: 0,
                                        fontFamily: 'monospace',
                                        fontSize: '0.75rem',
                                        maxWidth: '500px',
                                        overflow: 'auto'
                                    }}
                                >
                                    {jsonString}
                                </Box>
                            }
                            placement="left"
                            arrow
                        >
                            <Box
                                sx={{
                                    display: 'flex',
                                    alignItems: 'center',
                                    gap: 1,
                                    width: '100%'
                                }}
                            >
                                <CodeIcon fontSize="small" color="action" />
                                <Box
                                    sx={{
                                        fontFamily: 'monospace',
                                        fontSize: '0.75rem',
                                        whiteSpace: 'nowrap',
                                        overflow: 'hidden',
                                        textOverflow: 'ellipsis',
                                        flex: 1,
                                        color: 'text.secondary'
                                    }}
                                >
                                    {isLarge ? `${compactString.substring(0, 50)}...` : compactString}
                                </Box>
                            </Box>
                        </Tooltip>
                    );
                } catch (error) {
                    return <Typography variant="body2" color="error">Invalid JSON</Typography>;
                }
            },
        },
        {
            field: 'userId',
            headerName: 'User ID',
            width: 250,
            renderCell: (params) => (
                <Typography variant="body2" noWrap>
                    {params.value}
                </Typography>
            ),
        },
        {
            field: 'createdAt',
            headerName: 'Created At',
            width: 180,
            renderCell: (params) => params.value ? new Date(params.value).toLocaleString() : '',
        },
    ];

    const handleRowClick = async (params: GridRowParams) => {
        console.log("Event log row clicked:", params.row);
    };

    const refreshAll = async () => {
        await fetchEventLogs();
    };

    return (
        <Box sx={{ width: '100%', maxWidth: { sm: '100%', md: '1700px' } }}>
            <Box display="flex" justifyContent="left" alignItems="center">
                <Button
                    variant="text"
                    color="primary"
                    onClick={refreshAll}
                    startIcon={<SyncIcon />}
                    sx={{ padding: '10px 20px', borderRadius: '0' }}
                >
                    Refresh
                </Button>
            </Box>

            {eventLogRows.length === 0 ? (
                <Box mt={10} textAlign="center">
                    <Typography variant="h6">No event logs found</Typography>
                    <Divider orientation="vertical" variant="middle" flexItem />
                    <br />
                    <Typography variant="body2" color="text.secondary">
                        Event logs will appear here as actions are performed
                    </Typography>
                </Box>
            ) : (
                <>
                    <Box mt={4} sx={{ '& .MuiTypography-root': { fontWeight: 'bold' } }}>
                        <Card>
                            <CardContent>
                                <Box display="flex" justifyContent="space-between" alignItems="center">
                                    <Typography variant="h5" component="div" gutterBottom>
                                        Event Log
                                    </Typography>
                                    <Typography variant="body2" color="text.secondary">
                                        {eventLogRows.length} events
                                    </Typography>
                                </Box>
                            </CardContent>
                        </Card>
                    </Box>
                    <div style={{ width: '100%' }}>
                        <StyledDataGrid
                            initialState={{
                                pagination: { paginationModel: { pageSize: 25 } },
                                sorting: { sortModel: [{ field: 'createdAt', sort: 'desc' }] }
                            }}
                            rows={eventLogRows}
                            columns={columns}
                            getRowId={(row) => row.uuid}
                            pageSizeOptions={[10, 25, 50, 100]}
                            pagination
                            onRowClick={handleRowClick}
                        />
                    </div>
                </>
            )}
        </Box>
    );
};

export default EventLogGrid;
