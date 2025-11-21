import * as React from 'react';
import Stack from '@mui/material/Stack';
import NavbarBreadcrumbs from './NavbarBreadcrumbs';
import MenuButton from './MenuButton';
import {HelpRounded} from "@mui/icons-material";

interface HeaderProps {
    selectedMenuItem: string[];
    onDrawerToggle: () => void;
}

const Header: React.FC<HeaderProps> = ({selectedMenuItem, onDrawerToggle}) => {

    return (
        <Stack
            direction="row"
            sx={{
                display: {xs: 'none', md: 'flex'},
                width: '100%',
                alignItems: {xs: 'flex-start', md: 'center'},
                justifyContent: 'space-between',
                maxWidth: {sm: '100%', md: '1700px'},
                pt: 2,
            }}
            spacing={2}
        >
            <NavbarBreadcrumbs selectedMenuItem={selectedMenuItem}/>
            <Stack direction="row">
                { /* We will add the Search component here
        <Search />
        <CustomDatePicker />
        <MenuButton showBadge aria-label="Open notifications">
          <NotificationsRoundedIcon />
        </MenuButton>
        <ColorModeIconDropdown />
                  */
                    <>
                        <MenuButton aria-label="Open notifications" onClick={onDrawerToggle}>
                            <HelpRounded/>
                        </MenuButton>
                    </>

                }

            </Stack>
        </Stack>
    );
}

export default Header;
