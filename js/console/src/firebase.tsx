/*
// src/config/firebase.js
import {getAnalytics} from "firebase/analytics";

export const getFirebaseConfig = () => {
    if (!window.firebaseConfig) {
        throw new Error("Firebase config is not available");
    }
    return window.firebaseConfig;
};

import { initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";

const firebaseConfig = getFirebaseConfig();
const app = initializeApp(firebaseConfig);
export const analytics = getAnalytics(app);
export const auth = getAuth(app);

 */
// src/config/firebase.ts
// src/config/firebase.ts
import { getAnalytics } from "firebase/analytics";
import { initializeApp, FirebaseApp } from "firebase/app";
import { getAuth, Auth } from "firebase/auth";

interface FirebaseConfig {
    apiKey: string;
    authDomain: string;
    projectId: string;
    storageBucket: string;
    messagingSenderId: string;
    appId: string;
    measurementId?: string;
}

declare global {
    interface Window {
        firebaseConfig?: FirebaseConfig;
    }
}

export const getFirebaseConfig = (): FirebaseConfig => {
    if (!window.firebaseConfig) {
        throw new Error("Firebase config is not available");
    }
    return window.firebaseConfig;
};

const firebaseConfig: FirebaseConfig = getFirebaseConfig();
const app: FirebaseApp = initializeApp(firebaseConfig);
//export const analytics = getAnalytics(app);
export const auth: Auth = getAuth(app);