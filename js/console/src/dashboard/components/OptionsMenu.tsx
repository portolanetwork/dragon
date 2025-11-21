import * as React from 'react';
import {styled} from '@mui/material/styles';
import Divider, {dividerClasses} from '@mui/material/Divider';
import Menu from '@mui/material/Menu';
import MuiMenuItem from '@mui/material/MenuItem';
import {paperClasses} from '@mui/material/Paper';
import {listClasses} from '@mui/material/List';
import ListItemText from '@mui/material/ListItemText';
import ListItemIcon, {listItemIconClasses} from '@mui/material/ListItemIcon';
import MoreVertRoundedIcon from '@mui/icons-material/MoreVertRounded';
import MenuButton from './MenuButton';
import {useAuth0} from '@auth0/auth0-react';
import { Logout } from "@mui/icons-material";




const MenuItem = styled(MuiMenuItem)({
    margin: '2px 0',
});

export default function OptionsMenu() {
    const { logout } = useAuth0();
    const [anchorEl, setAnchorEl] = React.useState<null | HTMLElement>(null);
    const open = Boolean(anchorEl);
    const handleClick = (event: React.MouseEvent<HTMLElement>) => {
        setAnchorEl(event.currentTarget);
    };

    const handleClose = () => {
        setAnchorEl(null);
    };

    const handleLogout = () => {
        logout({
            logoutParams: {
                returnTo: window.location.origin
            }
        });
    };

    return (
        <React.Fragment>
            <MenuButton
                aria-label="Open menu"
                onClick={handleClick}
                sx={{borderColor: 'transparent'}}
            >
                <MoreVertRoundedIcon/>
            </MenuButton>
            <Menu
                anchorEl={anchorEl}
                id="menu"
                open={open}
                onClose={handleClose}
                onClick={handleClose}
                transformOrigin={{horizontal: 'right', vertical: 'top'}}
                anchorOrigin={{horizontal: 'right', vertical: 'bottom'}}
                sx={{
                    [`& .${listClasses.root}`]: {
                        padding: '4px',
                    },
                    [`& .${paperClasses.root}`]: {
                        padding: 0,
                    },
                    [`& .${dividerClasses.root}`]: {
                        margin: '4px -4px',
                    },
                }}
            >
                <Divider/>
                <MenuItem
                    onClick={handleLogout}
                    sx={{
                        [`& .${listItemIconClasses.root}`]: {
                            ml: 'auto',
                            minWidth: 0,
                            width: 80,
                        },
                    }}
                >
                    <ListItemText>Logout</ListItemText>
                </MenuItem>
            </Menu>
        </React.Fragment>
    );
}
