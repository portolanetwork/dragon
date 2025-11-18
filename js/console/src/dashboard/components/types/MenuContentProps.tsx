import * as React from "react";

export interface MenuItem {
    text: string;
    icon?: React.ReactNode;
    onClick: () => void;
    onDelete?: () => void;
    isSelected: boolean;
    isDisabled?: boolean;
    children?: MenuItem[];
}

export interface MenuContentProps {
    mainListItems: MenuItem[];
    middleListItems: MenuItem[];
    secondaryListItems: MenuItem[];
    //onMenuItemClick: (menuItem: string[]) => void;
}
