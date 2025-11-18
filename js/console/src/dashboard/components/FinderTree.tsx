import React, {StrictMode, useEffect, useRef, useState, useMemo, useCallback} from "react";
import {createRoot} from "react-dom/client";

import type {
    ColDef,
    GridApi,
    GridOptions,
    GridReadyEvent,
} from "ag-grid-community";
import {
    RowSelectionModule,
    ClientSideRowModelApiModule,
    ClientSideRowModelModule,
    DragAndDropModule,
    ModuleRegistry,
    RowApiModule,
    RowDragModule,
    RowStyleModule,
    TextFilterModule,
    ValidationModule,
} from "ag-grid-community";
import {AgGridReact} from "ag-grid-react";

import {Box} from "@mui/system";

import "./findertree.css";
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";
import FsBreadcrumbs from "./FsBreadcrumbs";
import {EndpointRow} from "./types/EndpointRow";
import {EndpointFilepathRow} from "./types/EndpointFilepathRow";
import {TextField, Slide} from "@mui/material";

import {RowNode} from "ag-grid-community";
import {colorSchemeVariable, QuickFilterModule} from "ag-grid-enterprise";
import AllTunnelsGrid from "./AllTunnelsGrid";
import {TunnelRow} from "./types/TunnelRow";
import {useColorScheme} from '@mui/material/styles';
import {NumberFilterModule} from 'ag-grid-community';
import FilterBar from "./FilterBar";


import {
    themeAlpine,
    themeBalham,
    themeMaterial,
    themeQuartz,
    colorSchemeDark,
    colorSchemeDarkBlue,
} from "ag-grid-community";
import RowOptionsRenderer from "./RowOptionsRenderer";
import FileCellRenderer from "./FileCellRenderer";

/*
const myTheme = themeQuartz
    .withPart(colorSchemeVariable)
    .withParams({
        accentColor: 'red',
    });

 */


const myTheme = themeBalham
    .withParams(
        {
            backgroundColor: '#F9F9F9',
            foregroundColor: '#333333',
            browserColorScheme: 'light',
        },
        'light'
    )
    .withParams(
        {
            backgroundColor: '#121212',
            foregroundColor: '#E0E0E0',
            browserColorScheme: 'dark',
        },
        'dark'
    );

ModuleRegistry.registerModules([
    RowSelectionModule,
    NumberFilterModule,
    DragAndDropModule,
    ClientSideRowModelApiModule,
    RowApiModule,
    TextFilterModule,
    RowDragModule,
    RowStyleModule,
    ClientSideRowModelModule,
    ValidationModule /* Development Only */,
    QuickFilterModule,
]);

const baseDefaultColDef: ColDef = {
    flex: 1,
    filter: true,
};

const baseGridOptions: GridOptions = {
    theme: myTheme,
    getRowId: (params) => {
        return String(params.data.name);
    },
    rowClassRules: {
        //"red-row": 'data.color == "Red"',
        "green-row": 'data.color == "Green"',
        "blue-row": 'data.color == "Blue"',
        "file-row": 'data.isDir == false',
        "pending-row": 'data.isPending == true',
    },
    rowDragManaged: true,
    animateRows: true,
};


const createPendingRow = (name: string, size: number, modTime: string, isDir: boolean) => {
    return {
        name: name + " (pending)",
        size: size,
        modTime: modTime,
        isDir: isDir,
        isPending: true,
    };
}


interface FinderTreeProps {
    tunnelRow: TunnelRow;
    onTunnelRefresh() : () => void;
}


const FinderTree: React.FC<FinderTreeProps> = ({tunnelRow, onTunnelRefresh}) => {
    const leftGridId = "leftGrid";
    const rightGridId = "rightGrid";

    const {mode, setMode} = useColorScheme();

    const [user, loading, error] = useAuthState(auth);
    //const [endpointRows, setEndpointRows] = useState<EndpointRow[]>([]);

    const [leftEndpointFilepath, setLeftEndpointFilepath] = useState<EndpointFilepathRow | null>(null);
    const [rightEndpointFilepath, setRightEndpointFilepath] = useState<EndpointFilepathRow | null>(null);

    const [leftFilterText, setLeftFilterText] = useState<string>("");
    const [rightFilterText, setRightFilterText] = useState<string>("");

    //const leftGridRef = useRef<AgGridReact>(null);
    //const rightGridRef = useRef<AgGridReact>(null);

    const [leftApi, setLeftApi] = useState<GridApi | null>(null);
    const [rightApi, setRightApi] = useState<GridApi | null>(null);

    const rowSelection = useMemo(() => {
        return {
            mode: 'multiRow'
        };
    }, []);

    const defaultColDef = useMemo<ColDef>(() => {
        return {
            flex: 1,
            minWidth: 80,
        };
    }, []);

    ///

    if (loading) {
        return <div>Loading...</div>;
    }

    if (!user) {
        console.error("User not logged in");
        return;
    }


    const baseColumnDefs: ColDef[] = [
        //{ headerName: "Is Dir", flex: 1, field: "isDir", cellRenderer: FileIconRenderer },
        /*
        {field: "name", flex: 4, rowDrag: true, dndSource: true, filter: true, cellRenderer: FileIconRenderer, rowDragText: (params, dragItemCount) => {
                if (dragItemCount > 1) {
                    return dragItemCount + " athletes";
                }
                return "adsfasdf";
            },},
         */
        {field: "name", flex: 8, dndSource: true, filter: true, cellRenderer: FileCellRenderer},
        {field: "size", flex: 3},
        {field: "modTime", flex: 4},
        {field: "isDir", flex: 1, filter: true},
        {
            field: "trash",
            headerName: "",
            cellRenderer: RowOptionsRenderer,
            flex: 1,
            sortable: false,
            filter: false,
        },
        {
            field: "isDotfile",
            hide: true,
            filter: true,
        },
    ];


    const gridOptions: GridOptions = {
        ...baseGridOptions,
        columnDefs: [...baseColumnDefs],
        defaultColDef: {
            ...baseDefaultColDef,
        },
    };


    useEffect(() => {
        console.log("Ppppppppppp p p  p p  p p p  p p p p  pMode: ", mode);

        document.body.dataset.agThemeMode = mode;

        console.log("------------- TunnelRow: ", tunnelRow);

        setLeftEndpointFilepath({
            endpointId: tunnelRow.left.endpointId,
            path: tunnelRow.left.homedir,
            homedir: tunnelRow.left.homedir,
            endpointIsOnline: tunnelRow.left.endpointIsOnline,
            hostname: tunnelRow.left.hostname
        });
        setRightEndpointFilepath({
            endpointId: tunnelRow.right.endpointId,
            path: tunnelRow.right.homedir,
            homedir: tunnelRow.right.homedir,
            endpointIsOnline: tunnelRow.right.endpointIsOnline,
            hostname: tunnelRow.right.hostname
        });
    }, [tunnelRow]);

    /*
    useEffect(() => {
        const fetchAllEndpoints = async () => {
            getAllEndpoints();
        };
        fetchAllEndpoints();
    }, []);
     */

    useEffect(() => {
        if (leftEndpointFilepath) {
            //readDir(leftEndpointFilepath, leftGridRef);
            if (leftApi) {
                readDir(leftEndpointFilepath, leftApi);
            }
            //readDir(leftEndpointFilepath, leftGridRef);
        }
    }, [leftEndpointFilepath, leftApi]);

    useEffect(() => {
        if (rightEndpointFilepath) {
            if (rightApi) {
                readDir(rightEndpointFilepath, rightApi);
            }
        }
    }, [rightEndpointFilepath, rightApi]);


    ///
    const displayHiddenLeft = async (selected: boolean) => {
        console.log("Display hidden files left: ", selected);

        if (selected) {
            leftApi?.setColumnFilterModel("isDotfile", null).then(() => {
                leftApi.onFilterChanged();
            });
        } else {
            leftApi?.setColumnFilterModel("isDotfile", {
                type: "equals",
                filter: "visible",
            }).then(() => {
                leftApi.onFilterChanged();
            });
        }
    };


    const displayHiddenRight = async (selected: boolean) => {
        console.log("Display hidden files right: ", selected);

        if (selected) {
            rightApi?.setColumnFilterModel("isDotfile", null).then(() => {
                rightApi.onFilterChanged();
            });
        } else {
            rightApi?.setColumnFilterModel("isDotfile", {
                type: "equals",
                filter: "visible",
            }).then(() => {
                rightApi.onFilterChanged();
            });
        }
    };

    ///

    /*
    const getAllEndpoints = async () => {
        await SpyderProxy.getInstance()
            .getAllEndpoints(user)
            .then((endpointList) => {
                setEndpointRows(endpointList);
            }).catch((error) => {
                console.log("Error fetching endpoints: ", error.message);
            });
    };

     */

    const readDir = async (
        endpointFilepath: EndpointFilepathRow,
        //gridRef: React.RefObject<AgGridReact>,
        gridApi: GridApi
    ) => {
        if (!endpointFilepath) {
            console.log("EndpointFilepath is null");
            return;
        }

        console.log(`Fetching endpoints for: `, endpointFilepath);
        const fileInfoArr = await SpyderProxy.getInstance()
            .readDir(user, endpointFilepath.endpointId, endpointFilepath.path);


        /*
        fileInfoArr.forEach((item) => {
            console.log("item: ", item);
        });
         */



        /*
        await clearData(gridRef?.current?.api);

        // Update the grid with the fetched data
        gridRef?.current?.api.applyTransaction({ add: fileInfoArr });

         */
        await clearData(gridApi);

        // Update the grid with the fetched data
        gridApi?.applyTransaction({add: fileInfoArr});
    };

    const createPath = async (
        //endpointFilepath: EndpointFilepathRow,
        endpointId: string,
        path: string,
        gridApi: GridApi,
    ) => {
        console.log("Creating path: ", endpointId, path);

        const fileInfoArr = await SpyderProxy.getInstance()
            .createPath(user, endpointId, path);

        await onLeftRefresh();
    }

    /*
    const trashPath = async (
        endpointFilepath: EndpointFilepathRow,
        //gridApi: GridApi,
    ) => {
        console.log("Trashing path: ", endpointFilepath);

        await SpyderProxy.getInstance()
            .trashPath(user, endpointFilepath.endpointId, endpointFilepath.path);

        await onLeftRefresh();
    }
     */


    const handleLeftFilterChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        console.log("Left filter changed: ", event.target.value);
        setLeftFilterText(event.target.value);
    };

    const handleRightFilterChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        console.log("Right filter changed: ", event.target.value);
        setRightFilterText(event.target.value);
    };



    const onLeftGridReady = async (params: GridReadyEvent) => {
        params.api.setGridOption("rowData", []);
        setLeftApi(params.api);
    };

    const onRightGridReady = (params: GridReadyEvent) => {
        params.api.setGridOption("rowData", []);
        setRightApi(params.api);
    };


    const clearData = async (gridApi: any) => {
        const rowData: any[] = [];

        gridApi!.forEachNode(function (node: RowNode) {
            rowData.push(node.data);
        });

        const res = gridApi!.applyTransaction({
            remove: rowData,
        })!;
    }

    const onLeftCellClicked = async (event: any) => {
        console.log("Cell clicked: ", event);

        // Only process clicks on directories
        if (!event.data.isDir) {
            return;
        }

        setLeftFilterText("");
        setLeftEndpointFilepath((prev) => prev ? {...prev, path: event.data.path} : null);
    };

    const onRightCellClicked = async (event: any) => {
        console.log("Cell clicked: ", event);

        // Only process clicks on directories
        if (!event.data.isDir) {
            return;
        }

        setRightFilterText("");
        setRightEndpointFilepath((prev) => prev ? {...prev, path: event.data.path} : null);
    };


    const onLeftDirectorySelect = async (event: string) => {
        console.log("Directory selected: ", event);

        setLeftFilterText("");
        setLeftEndpointFilepath((prev) => prev ? {...prev, path: event} : null);
    }

    const onRightDirectorySelect = async (event: string) => {
        console.log("Directory selected: ", event);

        setRightFilterText("");
        setRightEndpointFilepath((prev) => prev ? {...prev, path: event} : null);
    }

    const onLeftRefresh = async () => {
        console.log("Left refresh: ", event);

        // Just trigger a refresh by editing the path
        setLeftEndpointFilepath((prev) => prev ? {...prev, path: prev.path} : null);
    }

    const onRightRefresh = async () => {
        console.log("Right refresh: ", event);

        // Just trigger a refresh by editing the path
        setRightEndpointFilepath((prev) => prev ? {...prev, path: prev.path} : null);
    }

    const onLeftCreateDir = async (dirname: string) => {
        if (!leftEndpointFilepath || !leftApi) {
            console.error("Left endpoint or API is not initialized");
            return;
        }

        const absPath = leftEndpointFilepath.path;
        const newPath = `${absPath}/${dirname}`; // Combine the absolute path and directory name

        console.log("Creating directory at path: ", newPath);

        await createPath(leftEndpointFilepath.endpointId, newPath, leftApi); // Call createPath with the new path
        await onLeftRefresh(); // Refresh the left grid
    };

    const onRightCreateDir = async (dirname: string) => {
        console.log("Right create directory: ", event);

        if (!rightEndpointFilepath || !rightApi) {
            console.error("Right endpoint or API is not initialized");
            return;
        }
        const absPath = rightEndpointFilepath.path;
        const newPath = `${absPath}/${dirname}`; // Combine the absolute path and directory name

        console.log("Creating directory at path: ", newPath);

        await createPath(rightEndpointFilepath.endpointId, newPath, rightApi); // Call createPath with the new path
        await onRightRefresh(); // Refresh the right grid
    }

    const dragStart = (grid: string, event: any) => {
        //const newItem = createDataItem(color);
        //const jsonData = JSON.stringify(newItem);

        //event.dataTransfer.setData("application/json", jsonData);
        event.dataTransfer.setData('source-grid', grid);
    };


    const gridDragOver = (event: any) => {
        const dragSupported = event.dataTransfer.types.length;

        if (dragSupported) {
            event.dataTransfer.dropEffect = "copy";
            event.preventDefault();
        }
    };

    const gridDrop = (toGrid: string, event: any) => {
        event.preventDefault();

        const fromGrid = event.dataTransfer.getData('source-grid');

        if (fromGrid === toGrid) {
            console.log("Ignore: drag and drop in the same grid, do nothing");
            return;
        }

        console.log("gridDrop: ", toGrid, event);

        const jsonData = event.dataTransfer.getData("application/json");
        const data = JSON.parse(jsonData);

        console.log("gridDrop data: ", data);

        //console.log("gridDrop metadata: ", event.data.

        // if data missing or data has no it, do nothing
        if (!data || data.name == null) {
            return;
        }

        /*
        const toGridApi: GridApi =
            toGrid === "left" ? leftGridRef.current!.api : rightGridRef.current!.api;

        const fromGridapi =
            fromGrid === "left" ? leftGridRef.current!.api : rightGridRef.current!.api;
            //event.dataTransfer.getData('source-grid') === "left" ? leftGridRef.current!.api : rightGridRef.current!.api;
         */

        console.log("toGrid: ", toGrid);
        console.log("fromGrid: ", fromGrid);

        // Left is client, right is server
        // if (toGrid === "left") clientPath = data.path; serverPath = leftEndpointFilepath?.path;
        // if (toGrid === "right") clientPath = rightEndpointFilepath?.path; serverPath = data.path;


        if (leftEndpointFilepath != null && rightEndpointFilepath != null) {
            if (toGrid === "left") {
                leftApi?.applyTransaction({
                    add: [createPendingRow(data.name, data.size, data.modTime, data.isDir)],
                });

                SpyderProxy.getInstance().copyPath(
                    user,
                    tunnelRow.tunnelId,
                    leftEndpointFilepath.path,
                    data.path,
                    true)
                    .then((res) => {
                        setLeftEndpointFilepath((prev) => prev ? {...prev} : null);
                    });

            } else if (toGrid === "right") {
                rightApi?.applyTransaction({
                    add: [createPendingRow(data.name, data.size, data.modTime, data.isDir)],
                });

                SpyderProxy.getInstance().copyPath(
                    user,
                    tunnelRow.tunnelId,
                    data.path,
                    rightEndpointFilepath.path,
                    false)
                    .then((res) => {
                        setRightEndpointFilepath((prev) => prev ? {...prev} : null);
                    });


            }
        }

    };


    const handleLeftEndpointUnselected = () => {
        setLeftEndpointFilepath(null);
    }


    const handleRightEndpointUnselected = () => {
        setRightEndpointFilepath(null);
    }

    /*
    const handleEndpointSelected = async (endpoint: EndpointRow) => {
        console.log("Dialog closed with endpoint: ", endpoint);

    }

    const quickFilterMatcher = (quickFilterParts, rowQuickFilterAggregateText) => {
        return quickFilterParts.every(part => rowQuickFilterAggregateText.match(part));
    };
     */

    //const quickFilterText = 'new filter text';

    const handleDialogClose = (endpoint: EndpointRow) => {
        console.log("Dialog closed with endpoint: ", endpoint);
        //setLeftEndpointFilepath({endpointId: endpoint.id, path: endpoint.homedir});
    }

    const handleEndpointsSelected = (leftEndpointFilePath: EndpointFilepathRow | null, rightEndpointFilePath: EndpointFilepathRow | null) => {
        console.log("Dialog closed with endpoints: ", leftEndpointFilePath, rightEndpointFilePath);
        if (leftEndpointFilePath) {
            setLeftEndpointFilepath(leftEndpointFilePath);
        }
        if (rightEndpointFilePath) {
            setRightEndpointFilepath(rightEndpointFilePath);
        }

        //setIsFormVisible(true);
    }

    //const [isFormVisible, setIsFormVisible] = useState(false);


    return (
        <Box sx={{width: '100%', maxWidth: {sm: '100%', md: '1700px'}}}>
            {leftEndpointFilepath && rightEndpointFilepath &&
                <>
                    <Box sx={{p: 2}}/>
                    <Box className="outer">
                        <Box
                            style={{height: "70vh"}}
                            //style={{ height: "calc(100vh)" }} // Adjust 20px as needed for padding/margin
                            className="inner-col"
                            onDragOver={gridDragOver}
                            onDragStart={(e) => dragStart("left", e)}
                            onDrop={(e) => gridDrop("left", e)}
                        >
                            <>
                                <FsBreadcrumbs homedir={leftEndpointFilepath.homedir}
                                               endpointFilePath={leftEndpointFilepath}
                                               onDirectorySelect={onLeftDirectorySelect}
                                               onRefresh={onLeftRefresh}
                                               onCreateDir={onLeftCreateDir}
                                />
                                <Box sx={{p: 1}}/>
                                <FilterBar filterText={leftFilterText}
                                           onFilterChange={handleLeftFilterChange}
                                           onDisplayHiddenFiles={displayHiddenLeft}
                                           onButton2Click={() => {}}
                                />

                                <AgGridReact
                                    //rowDragMultiRow={true}
                                    //rowDragManaged={true}
                                    //ref={leftGridRef}
                                    //rowSelection={rowSelection}
                                    context={{
                                        gridId: leftGridId,
                                        user: user,
                                        endpointFilepath: leftEndpointFilepath,
                                        onCellClicked: onLeftCellClicked,
                                        onRefresh: onLeftRefresh,
                                    }}
                                    defaultColDef={defaultColDef}
                                    quickFilterText={leftFilterText}
                                    gridOptions={gridOptions}
                                    onGridReady={onLeftGridReady}
                                    //onCellClicked={onLeftCellClicked}
                                />
                            </>
                        </Box>
                        <Box sx={{p: 2}}/>
                        <Box
                            style={{height: "70vh"}}
                            className="inner-col"
                            onDragOver={gridDragOver}
                            onDragStart={(e) => dragStart("right", e)}
                            onDrop={(e) => gridDrop("right", e)}
                        >
                            <>
                                <FsBreadcrumbs homedir={rightEndpointFilepath.homedir}
                                               endpointFilePath={rightEndpointFilepath}
                                               onDirectorySelect={onRightDirectorySelect}
                                               onRefresh={onRightRefresh}
                                               onCreateDir={onRightCreateDir}
                                />
                                <Box sx={{p: 1}}/>

                                <FilterBar
                                    filterText={rightFilterText}
                                    onFilterChange={handleRightFilterChange}
                                    onDisplayHiddenFiles={displayHiddenRight}
                                    onButton2Click={() => {}}
                                />
                                <AgGridReact
                                    //rowDragMultiRow={true}
                                    //rowDragManaged={true}

                                    //ref={rightGridRef}
                                    //rowSelection={rowSelection}
                                    context={{
                                        gridId: rightGridId,
                                        user: user,
                                        endpointFilepath: rightEndpointFilepath,
                                        onCellClicked: onRightCellClicked,
                                        onRefresh: onRightRefresh,
                                    }}
                                    defaultColDef={defaultColDef}
                                    quickFilterText={rightFilterText}
                                    gridOptions={gridOptions}
                                    onGridReady={onRightGridReady}
                                    //onCellClicked={onRightCellClicked}
                                />
                            </>
                        </Box>
                    </Box>

                </>
            }

        </Box>
    );
};

export default FinderTree;


