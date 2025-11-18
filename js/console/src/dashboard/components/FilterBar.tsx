import React, {useEffect, useState} from 'react';
import { Button, Box, TextField, Tooltip, useTheme } from '@mui/material';
import AdjustIcon from '@mui/icons-material/Adjust';

interface FilterBarProps {
    filterText: string;
    onFilterChange: (event: React.ChangeEvent<HTMLInputElement>) => void;
    onDisplayHiddenFiles: (selected: boolean) => void;
    onButton2Click: () => void;
}

const FilterBar: React.FC<FilterBarProps> = ({ filterText, onFilterChange, onDisplayHiddenFiles, onButton2Click }) => {
    const [isDisplayHiddenFilesSelected, setIsDisplayHiddenFilesSelected] = useState(true);
    //const theme = useTheme();

    useEffect(() => {
        // Initialize the state based on the prop value
        onDisplayHiddenFiles(isDisplayHiddenFilesSelected); // Default to true, or set based on a prop if needed
    }, [isDisplayHiddenFilesSelected]);


    const handleToggle = () => {
        //onDisplayHiddenFiles(isDisplayHiddenFilesSelected); // Call the function passed as prop
        console.log("Toggle display hidden files:", isDisplayHiddenFilesSelected);
        setIsDisplayHiddenFilesSelected((prev) => !prev); // Toggle the state
    };

    const geDisplayHiddenTooltipText = () => {
        return isDisplayHiddenFilesSelected ? "Display hidden files: On" : "Display hidden files: Off";
    }

    return (
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 2 }}>
            <TextField
                label="Quick Filter"
                variant="outlined"
                size="small"
                fullWidth
                value={filterText}
                onChange={onFilterChange}
                sx={{ ml: 'auto', flexGrow: 1 }}
            />
            <Box sx={{ display: 'flex', flexDirection: 'row', ml: 2, gap: 1 }}>
                <Tooltip title={geDisplayHiddenTooltipText()} arrow>
                    <Button
                        variant="contained"
                        onClick={handleToggle}
                        color={isDisplayHiddenFilesSelected ? "secondary" : "primary"} // Toggle color
                        size="small"
                        sx={{
                            width: '35px',
                            height: '35px',
                            minWidth: '35px',
                            padding: 0,
                            fontSize: '0.75rem',
                        }}
                    >
                        <AdjustIcon />
                    </Button>
                </Tooltip>
            </Box>
        </Box>
    );
};

export default FilterBar;