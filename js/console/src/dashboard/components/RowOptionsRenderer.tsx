import React, {useState} from "react";
import {Box, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Button, Chip} from "@mui/material";
import DeleteOutlineOutlinedIcon from "@mui/icons-material/DeleteOutlineOutlined";
import SpyderProxy from "../../spyder_proxy/SpyderProxy";
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import MoreVertIcon from '@mui/icons-material/MoreVert';
import IconButton from '@mui/material/IconButton';
import TrashConfirmDialog from "./TrashConfirmDialog";
import RenameFileDialog from "./RenameFileDialog";

const RowOptionsRenderer = (props: any) => {
    const [open, setOpen] = useState(false);

    const [openTrashDialog, setOpenTrashDialog] = useState(false);
    const [openRenameDialog, setOpenRenameDialog] = useState(false);

    const [selectedRow, setSelectedRow] = useState<any>(null);
    const [newFileName, setNewFileName] = useState("");


    const [anchorEl, setAnchorEl] = React.useState<null | HTMLElement>(null);

    const openMenu = Boolean(anchorEl);

    const handleClick = (event: React.MouseEvent<HTMLElement>) => {
        console.log("Clicked on button");
        setAnchorEl(event.currentTarget);
    };
    const handleClose = () => {
        setAnchorEl(null);
    };

    const handleTrashClick = (event: React.MouseEvent) => {

        console.log("Trash clicked for row: ", props.data);
        console.log("Trash clicked for row props: ", props);

        event.stopPropagation(); // Prevent row selection
        setSelectedRow(props.data); // Store the row data
        setOpen(true); // Open the dialog
    };

    const handleRenameClick = (event: React.MouseEvent) => {
        event.stopPropagation();
        setSelectedRow(props.data);
        setNewFileName(props.data.name); // Pre-fill with the current file name
        setOpenRenameDialog(true);
    };

    const handleConfirmTrash = async () => {
        setOpen(false); // Close the dialog

        if (props.context.gridId === "leftGrid") {
            await SpyderProxy.getInstance()
                .trashPath(props.context.user, props.context.endpointFilepath.endpointId, selectedRow.path);
        } else if (props.context.gridId === "rightGrid") {
            await SpyderProxy.getInstance()
                .trashPath(props.context.user, props.context.endpointFilepath.endpointId, selectedRow.path);
        }

        props.context.onRefresh(); // Refresh the grid
    };


    const handleConfirmRename = async (newName: string) => {
        setOpenRenameDialog(false);

        let oldPath = selectedRow.path;

        // Extract the directory path from the oldPath
        const directoryPath = oldPath.substring(0, oldPath.lastIndexOf("/") + 1);

        // Construct the new path by appending the new name
        const newPath = `${directoryPath}${newName}`;

        if (props.context.gridId === "leftGrid" || props.context.gridId === "rightGrid") {
            await SpyderProxy.getInstance().renamePath(
                props.context.user,
                props.context.endpointFilepath.endpointId,
                selectedRow.path,
                newPath,
            );
        }
        props.context.onRefresh();
    };

    const handleCancel = () => {
        setOpen(false); // Close the dialog
    };

    const handleCancelRename = () => {
        setOpenRenameDialog(false);
    };


    return (
        <>
            <Box
                sx={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    height: '100%',
                }}
            >
                <IconButton
                    onClick={handleClick}
                    style={{
                        padding: '4px',
                        //backgroundColor: 'transparent',
                    }}
                >
                    <MoreVertIcon
                        style={{
                            fontSize: '16px',
                        }}
                    />
                </IconButton>
            </Box>
            <Menu
                id="demo-positioned-menu"
                aria-labelledby="demo-positioned-button"
                anchorEl={anchorEl}
                open={openMenu}
                onClose={handleClose}
                anchorOrigin={{
                    vertical: 'top',
                    horizontal: 'left',
                }}
                transformOrigin={{
                    vertical: 'top',
                    horizontal: 'left',
                }}
            >
                <MenuItem onClick={handleTrashClick}>Move to Trash</MenuItem>
                <MenuItem onClick={handleRenameClick}>Rename</MenuItem>
            </Menu>

            {/*
                        <Chip
                label={"Trash"}
                color={"secondary"}
                sx={{ fontSize: '12px', height: '24px', lineHeight: '24px' }}
                icon={<DeleteOutlineOutlinedIcon />}
                onClick={handleTrashClick}
            />
            */
            }
            {/*
            <DeleteOutlineOutlinedIcon
                style={{ cursor: "pointer", color: "red" }}
                onClick={handleTrashClick}
            />
            */}
            <TrashConfirmDialog
                open={open}
                selectedRow={selectedRow}
                onCancel={handleCancel}
                onConfirm={handleConfirmTrash}
            />
            <RenameFileDialog
                open={openRenameDialog}
                selectedRow={selectedRow}
                //newName={newFileName}
                onCancel={handleCancelRename}
                onConfirm={handleConfirmRename}
            />
        </>
    );
};

export default RowOptionsRenderer;