import * as React from 'react';
import {Box, Typography, Switch, FormControlLabel, Button, Card, CardContent} from '@mui/material';
import { useColorScheme } from '@mui/material/styles';
import FormControl from '@mui/material/FormControl';
import FormLabel from '@mui/material/FormLabel';
import RadioGroup from '@mui/material/RadioGroup';
import Radio from '@mui/material/Radio';
import Subscription from './Subscription';

const Settings: React.FC = () => {
    const { mode, setMode } = useColorScheme();
    //const [darkMode, setDarkMode] = React.useState(mode === 'dark');

    /*
    const handleDarkModeChange = (event: React.ChangeEvent<HTMLInputElement>) => {
        setDarkMode(event.target.checked);
        setMode(event.target.checked ? 'dark' : 'light');
    };

     */

    return (
        <Box sx={{width: '100%', maxWidth: {sm: '100%', md: '1700px'}}}>
            <Box mt={4} sx={{'& .MuiTypography-root': {fontWeight: 'bold'}}}>
                <Card>
                    <CardContent>
                        <Typography variant="h5" component="div" gutterBottom>
                            Settings
                        </Typography>
                    </CardContent>
                </Card>
            </Box>
            <Box sx={{p: 2}} />
            <Box
                sx={{
                    display: 'flex',
                    width: '100%',
                    alignItems: 'center',
                    justifyContent: 'left',
                    bgcolor: 'background.default',
                    color: 'text.primary',
                    borderRadius: 1,
                    p: 3,
                    minHeight: '56px',
                }}
            >
                <FormControl>
                    <FormLabel id="demo-theme-toggle">Theme</FormLabel>
                    <RadioGroup
                        aria-labelledby="demo-theme-toggle"
                        name="theme-toggle"
                        row
                        value={mode}
                        onChange={(event) =>
                            setMode(event.target.value as 'system' | 'light' | 'dark')
                        }
                    >
                        <FormControlLabel value="system" control={<Radio />} label="System" />
                        <FormControlLabel value="light" control={<Radio />} label="Light" />
                        <FormControlLabel value="dark" control={<Radio />} label="Dark" />
                    </RadioGroup>
                </FormControl>
            </Box>
            { /* <Subscription /> */ }
        </Box>
    );
};

export default Settings;