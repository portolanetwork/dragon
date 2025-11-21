import * as React from 'react';
import type {} from '@mui/x-date-pickers/themeAugmentation';
import type {} from '@mui/x-charts/themeAugmentation';
import type {} from '@mui/x-data-grid/themeAugmentation';
import type {} from '@mui/x-tree-view/themeAugmentation';
import {useState} from 'react';
import { alpha } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import AppNavbar from './components/AppNavbar';
import Header from './components/Header';
import SideMenu from './components/SideMenu';
import AppTheme from '../shared-theme/AppTheme';
import Settings from './components/Settings';
import Drawer from '@mui/material/Drawer';
import Typography from '@mui/material/Typography';
import Toolbar from '@mui/material/Toolbar';
import HelpContent from './components/HelpContent';
import { MenuItem } from './components/types/MenuContentProps';

import SettingsRoundedIcon from '@mui/icons-material/SettingsRounded';
import StorageIcon from '@mui/icons-material/Storage';



import {
  chartsCustomizations,
  treeViewCustomizations,
} from './theme/customizations';
import McpServersGrid from "./components/McpServersGrid";
import EditMcpServer from "./components/EditMcpServer";
import AddMcpServer from "./components/AddMcpServer";
import {useEffect} from "react";


const xThemeComponents = {
  ...chartsCustomizations,
  ...treeViewCustomizations,
};


const drawerWidth = 400;


export default function Dashboard(props: { disableCustomTheme?: boolean }) {
    const [selectedMenuItem, setSelectedMenuItem] = React.useState<string[]>(['MCP Servers']);
    const [drawerOpen, setDrawerOpen] = React.useState(false);
    const [selectedMcpServerUuid, setSelectedMcpServerUuid] = useState<string | null>(null);
    const [mcpServersRefreshTrigger, setMcpServersRefreshTrigger] = useState(0);

    const [mainMenuProps, setMainMenuProps] = useState<MenuItem[]>([]);
    const [secondaryMenuProps, setSecondaryMenuProps] = useState<MenuItem[]>([]);


    useEffect(() => {
        setMainMenuProps(mainListItems(handleMenuItemClick));
        setSecondaryMenuProps(secondaryListItems(handleMenuItemClick));
    }, [selectedMenuItem]);

    const handleDrawerToggle = () => {
        setDrawerOpen(!drawerOpen);
    };

    const handleMenuItemClick = (menuItem: string[]) => {
        console.log(`Menu item clicked: ${menuItem}`);
        setSelectedMenuItem(menuItem);
    };

    const mainListItems = (
        onMenuItemClick: (menuItem: string[]) => void
    ): MenuItem[] => {
        return [
            { text: 'MCP Servers', isSelected:selectedMenuItem.at(0) == 'MCP Servers', icon: <StorageIcon />, onClick: () => onMenuItemClick(['MCP Servers']) },
        ]};

    const secondaryListItems = (onMenuItemClick: (menuItem: string[]) => void) => [
        { text: 'Settings', isSelected:selectedMenuItem.at(0) == 'Settings', icon: <SettingsRoundedIcon />, onClick: () => onMenuItemClick(['Settings']) },
    ];

    const handleMcpServerSelect = (serverUuid: string) => {
        setSelectedMcpServerUuid(serverUuid);
        setSelectedMenuItem(['Edit MCP Server', serverUuid]);
    };

    const handleEditMcpServerBack = () => {
        setSelectedMcpServerUuid(null);
        setMcpServersRefreshTrigger(prev => prev + 1); // Trigger refresh
        setSelectedMenuItem(['MCP Servers']);
    };

    const handleAddMcpServer = () => {
        setSelectedMenuItem(['Add MCP Server']);
    };

    const handleAddMcpServerBack = () => {
        setMcpServersRefreshTrigger(prev => prev + 1); // Trigger refresh
        setSelectedMenuItem(['MCP Servers']);
    };

    return (
    <AppTheme {...props} themeComponents={xThemeComponents}>
        {  // Some padding behind the app bar
            <Box sx={{ height: '6vh'}}></Box>
        }
      <CssBaseline enableColorScheme />
      <Box sx={{ display: 'flex', mt: 0 }}>
        <SideMenu
            mainListItems={mainMenuProps}
            middleListItems={[]}
            secondaryListItems={secondaryMenuProps}
        />

        <AppNavbar />

          {/* Main content */}
        <Box
          component="main"
          sx={(theme) => ({
            flexGrow: 1,
            backgroundColor: theme.vars
              ? `rgba(${theme.vars.palette.background.defaultChannel} / 1)`
              : alpha(theme.palette.background.default, 1),
            overflow: 'auto',
              marginRight: drawerOpen ? 0: `${-drawerWidth}px` ,
              transition: theme.transitions.create('margin', {
                  easing: theme.transitions.easing.sharp,
                  duration: theme.transitions.duration.leavingScreen,
              }),
          })}
        >
            <Box sx={{ height: '94vh', overflow: 'auto' }}>
                <Stack
                    spacing={2}
                    sx={{
                        alignItems: 'center',
                        mx: 3, // Right margin
                        //pb: 5,
                        mt: { xs: 8, md: 0 },
                    }}
                >
                    <Header selectedMenuItem={selectedMenuItem} onDrawerToggle={handleDrawerToggle} />
                    {selectedMenuItem.at(0) === 'MCP Servers' && (
                        <McpServersGrid
                            onServerSelect={handleMcpServerSelect}
                            onAddServer={handleAddMcpServer}
                            refreshTrigger={mcpServersRefreshTrigger}
                        />
                    )}
                    {selectedMenuItem.at(0) === 'Add MCP Server' && (
                        <AddMcpServer onGoBack={handleAddMcpServerBack} />
                    )}
                    {selectedMenuItem.at(0) === 'Edit MCP Server' && selectedMcpServerUuid && (
                        <EditMcpServer serverUuid={selectedMcpServerUuid} onGoBack={handleEditMcpServerBack} />
                    )}
                    {selectedMenuItem.at(0) === 'Settings' && <Settings />}
                </Stack>
            </Box>
        </Box>

          <Drawer
              ////variant="permanent"
              variant="persistent"
              anchor="right"
              //open={drawerOpen}

              //variant="permanent"
              sx={{
                  width: drawerWidth,
                  flexShrink: 0,
                  mt: 1,
                  [`& .MuiDrawer-paper`]: { width: drawerWidth, boxSizing: 'border-box' },
              }}

              //open={isHelpDrawerOpen}
              //onClose={toggleHelpDrawer(false)}
              open={drawerOpen}
          >
              <Box sx={{ height: '10vh'}}></Box>

              { /* <Toolbar /> */ }

              <Box sx={{ overflow: 'auto', ml: 2, mr: 2 }}>
                  <HelpContent selectedMenuItem={selectedMenuItem} />
              </Box>
          </Drawer>

      </Box>

    </AppTheme>
  );
}
