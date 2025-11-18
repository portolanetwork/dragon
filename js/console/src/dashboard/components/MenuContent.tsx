import * as React from 'react';
import {useEffect, useState} from 'react';
import List from '@mui/material/List';
import Box from '@mui/material/Box';
import ListItem from '@mui/material/ListItem';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import {useAuthState} from "react-firebase-hooks/auth";
import {auth} from "../../firebase";
import {MenuContentProps} from "./types/MenuContentProps";
import DeleteIcon from '@mui/icons-material/Delete';
import Typography from "@mui/material/Typography";
import ClearIcon from '@mui/icons-material/Clear';
import Divider from '@mui/material/Divider';


export default function MenuContent({
                                        mainListItems = [],
                                        middleListItems = [],
                                        secondaryListItems = [],
                                    }: MenuContentProps) {
    const [user] = useAuthState(auth);
    //const [selectedIndex, setSelectedIndex] = useState(0);

    if (!user) {
        throw new Error("User is not authenticated");
    }

    //const handleListItemClick = (index: number, onClick: () => void) => {
        //setSelectedIndex(index);
        //onClick();
    //};

    return (
        <Stack sx={{flexGrow: 1, p: 1, height: '100%'}}>
            <Box>
                <List dense>
                    {mainListItems.map((item, index) => (
                        <React.Fragment key={index}>
                            <ListItem disablePadding sx={{display: 'block'}}>
                                <ListItemButton
                                    disabled={item.isDisabled ?? false}
                                    //selected={selectedIndex === index}
                                    //onClick={() => handleListItemClick(index, item.onClick)}>
                                    selected={item.isSelected}
                                    onClick={item.onClick}>
                                    <ListItemIcon>{item.icon}</ListItemIcon>
                                    <ListItemText primary={item.text}/>
                                </ListItemButton>
                            </ListItem>
                            {item.children && (
                                <Box sx={{maxHeight: '70vh' ,overflow: 'auto', pl: 0}}>
                                    <List component="div" disablePadding dense>
                                        {item.children.map((child, childIdx) => (
                                            <ListItem key={childIdx} disablePadding>
                                                <ListItemButton
                                                    selected={child.isSelected}
                                                    onClick={child.onClick}>
                                                    <ListItemText primary={
                                                        <Typography sx={{ fontSize: '0.75rem' }} noWrap>
                                                            {child.text}
                                                        </Typography>
                                                    }
                                                    />

                                                    <ListItemIcon>
                                                        <ClearIcon
                                                            sx={{cursor: 'pointer'}}
                                                            onClick={e => {
                                                                e.stopPropagation();
                                                                child.onDelete && child.onDelete();
                                                            }}
                                                        />
                                                    </ListItemIcon>
                                                </ListItemButton>
                                            </ListItem>
                                        ))}
                                    </List>
                                </Box>
                            )}

                        </React.Fragment>
                    ))}
                </List>
            </Box>
            <Box sx={{
                flexGrow: 1,
                overflow: 'auto',
                display: 'flex',
                flexDirection: 'column'
            }}>
                <List dense>
                    {middleListItems.map((item, index) => (
                        <React.Fragment key={index}>
                            <ListItem disablePadding sx={{display: 'block'}}>
                                <ListItemButton
                                    disabled={item.isDisabled ?? false}
                                    selected={item.isSelected}
                                    onClick={item.onClick}>
                                    <ListItemIcon>{item.icon}</ListItemIcon>
                                    <ListItemText primary={item.text}/>
                                </ListItemButton>
                            </ListItem>
                            {item.children && (
                                <Box sx={{maxHeight: '70vh' ,overflow: 'auto', pl: 0}}>
                                    <List component="div" disablePadding dense>
                                        {item.children.map((child, childIdx) => (
                                            <ListItem key={childIdx} disablePadding>
                                                <ListItemButton
                                                    selected={child.isSelected}
                                                    onClick={child.onClick}>
                                                    <ListItemText primary={
                                                        <Typography sx={{ fontSize: '0.75rem' }} noWrap>
                                                            {child.text}
                                                        </Typography>
                                                    }
                                                    />

                                                    <ListItemIcon>
                                                        <ClearIcon
                                                            sx={{cursor: 'pointer'}}
                                                            onClick={e => {
                                                                e.stopPropagation();
                                                                child.onDelete && child.onDelete();
                                                            }}
                                                        />
                                                    </ListItemIcon>
                                                </ListItemButton>
                                            </ListItem>
                                        ))}
                                    </List>
                                </Box>
                            )}
                        </React.Fragment>
                    ))}
                </List>
            </Box>
            <Box sx={{marginTop: 'auto'}}>
                <List dense>
                    {secondaryListItems.map((item, index) => (
                        <ListItem key={index} disablePadding sx={{display: 'block'}}>
                            <ListItemButton
                                //selected={selectedIndex === index + mainListItems.length}
                                //onClick={() => handleListItemClick(index, item.onClick)}>
                                selected={item.isSelected}
                                onClick={item.onClick}>
                                <ListItemIcon>{item.icon}</ListItemIcon>
                                <ListItemText primary={item.text}/>
                            </ListItemButton>
                        </ListItem>
                    ))}
                </List>
            </Box>
        </Stack>
    );
}