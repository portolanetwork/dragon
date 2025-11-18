import React from "react";
import { Box } from "@mui/material";
import InsertDriveFileIcon from "@mui/icons-material/InsertDriveFile";
import FolderIcon from "@mui/icons-material/Folder";
import MoreVertIcon from "@mui/icons-material/MoreVert";
import ArrowForwardIosOutlinedIcon from '@mui/icons-material/ArrowForwardIosOutlined';
import NavigateNextOutlinedIcon from '@mui/icons-material/NavigateNextOutlined';


const FileCellRenderer = (props: any) => {
    const handleClick = (event: React.MouseEvent) => {
        event.stopPropagation(); // Prevent row selection
        console.log("File clicked for row: ", props.data);
        console.log("File clicked for row props: ", props);

        // Perform any additional actions here
        if (props.context.onCellClicked) {
            props.context.onCellClicked(props);
        }
    };

    return (
        <span
            style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                cursor: 'pointer',
                width: '100%',
            }}
            onClick={handleClick}
        >
        <span
            style={{
                display: 'flex',
                alignItems: 'center',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                flexGrow: 1,
            }}
        >
            {props.data.isDir ? (
                <FolderIcon style={{ fontSize: 20, marginRight: 8, flexShrink: 0 }} />
            ) : (
                <InsertDriveFileIcon style={{ fontSize: 20, marginRight: 8, flexShrink: 0 }} />
            )}
            {props.value}
        </span>
            {props.data.isDir && (
                <NavigateNextOutlinedIcon style={{ color: "blue", flexShrink: 0 }} />
            )}
    </span>
    );
};

export default FileCellRenderer;