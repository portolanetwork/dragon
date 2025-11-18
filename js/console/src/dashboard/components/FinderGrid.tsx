// src/dashboard/components/FinderGrid.tsx
import React from "react";
import { AgGridReact } from "ag-grid-react";
import type { ColDef } from "ag-grid-community";

interface FinderGridProps {
    quickFilterText: string;
    context?: any;
    rowData?: any[];
    columnDefs: ColDef[];
    onApiReady?: (api: any) => void;
}

const baseDefaultColDef: ColDef = {
    flex: 1,
    minWidth: 80,
    filter: true,
};

const FinderGrid: React.FC<FinderGridProps> = ({
                                                   quickFilterText,
                                                   context,
                                                   rowData = [],
                                                   columnDefs,
                                                   onApiReady,
                                               }) => {
    const handleGridReady = (params: any) => {
        if (onApiReady) onApiReady(params.api);
    };

    return (
        <AgGridReact
            defaultColDef={baseDefaultColDef}
            quickFilterText={quickFilterText}
            context={context}
            rowData={rowData}
            columnDefs={columnDefs}
            onGridReady={handleGridReady}
        />
    );
};

export default FinderGrid;