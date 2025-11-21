import * as React from 'react';
import { styled } from '@mui/material/styles';
import Typography from '@mui/material/Typography';
import Breadcrumbs, { breadcrumbsClasses } from '@mui/material/Breadcrumbs';
import NavigateNextRoundedIcon from '@mui/icons-material/NavigateNextRounded';

interface NavbarBreadcrumbsProps {
  selectedMenuItem: string[];
}

const StyledBreadcrumbs = styled(Breadcrumbs)(({ theme }) => ({
  margin: theme.spacing(1, 0),
  [`& .${breadcrumbsClasses.separator}`]: {
    color: (theme.vars || theme).palette.action.disabled,
    margin: 1,
  },
  [`& .${breadcrumbsClasses.ol}`]: {
    alignItems: 'center',
  },
}));

const NavbarBreadcrumbs: React.FC<NavbarBreadcrumbsProps> = ({ selectedMenuItem }) => {
  return (
      <StyledBreadcrumbs
          aria-label="breadcrumb"
          separator={<NavigateNextRoundedIcon fontSize="small" />}
      >
          <Typography variant="body1">Console</Typography>
          {selectedMenuItem.map((item, idx) => (
              <Typography
                  key={item + idx}
                  variant="body1"
                  sx={{ color: idx === selectedMenuItem.length - 1 ? 'text.primary' : 'inherit', fontWeight: idx === selectedMenuItem.length - 1 ? 600 : 400 }}
              >
                  {item}

              </Typography>
          ))}
      </StyledBreadcrumbs>

  );
};

export default NavbarBreadcrumbs;