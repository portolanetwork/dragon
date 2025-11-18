import React from 'react';
import { DataGrid, GridRowsProp, GridColDef, DataGridProps } from '@mui/x-data-grid';
import { useTheme } from '@mui/material/styles';

interface StyledDataGridProps extends DataGridProps {
    rows: GridRowsProp;
    columns: GridColDef[];
}

const StyledDataGrid: React.FC<StyledDataGridProps> = (props) => {
    const theme = useTheme();

    return (
        <DataGrid
            {...props}
            getRowClassName={(params) =>
                params.indexRelativeToCurrentPage % 2 === 0 ? 'even' : ''
            }
            sx={{
                '& .MuiDataGrid-cell': {
                    fontWeight: 'bold',
                },
                '& .MuiDataGrid-row.even': {
                    backgroundColor: theme.palette.action.hover,
                },
                ...props.sx,
            }}
        />
    );
};

export default StyledDataGrid;