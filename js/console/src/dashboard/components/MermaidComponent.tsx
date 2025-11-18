import React from 'react';
import Mermaid from 'react-mermaid2';
import { useTheme } from '@mui/material/styles';
import { Box } from '@mui/material';

interface MermaidComponentProps {
    chart: string;
}

const MermaidComponent: React.FC<MermaidComponentProps> = ({ chart }) => {
    const theme = useTheme();
    const isDark = theme.palette.mode === 'dark';

    const mermaidConfig = {
        theme: isDark ? 'dark' : 'default',
        themeVariables: {
            primaryColor: theme.palette.primary.main,
            primaryTextColor: theme.palette.text.primary,
            primaryBorderColor: theme.palette.divider,
            lineColor: theme.palette.divider,
            secondaryColor: theme.palette.secondary.main,
            tertiaryColor: theme.palette.background.paper,
        },
    };

    return (
        <Box sx={{ 
            my: 2, 
            p: 1, 
            border: 1, 
            borderColor: 'divider', 
            borderRadius: 1,
            backgroundColor: 'background.paper',
            overflow: 'auto'
        }}>
            <Mermaid chart={chart} config={mermaidConfig} />
        </Box>
    );
};

export default MermaidComponent;