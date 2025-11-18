// src/utils/WebSocketClient.ts
export class SpyderWssClient {
    private socket: WebSocket | null = null;
    private url: string = "";

    constructor(
        //private url: string,
        private token: string,
        private chatId: string,
        private onMessage: (data: any) => void,
        private onOpen?: () => void,
        private onError?: (error: Event) => void,
        private onClose?: () => void
    ) {
        // const wsUrl = `wss://spyderweb.staging-app.portolanetwork.com/wss/dragon/chat?auth=${encodeURIComponent(token)}&chatId=${encodeURIComponent(chatId)}`;
        //console.log("------------ WebSocket token:", token);
        //console.log("------------ WebSocket chatId:", chatId);

        if (window.location.hostname === 'localhost') {
            this.url = `wss://spyderweb.staging-app.portolanetwork.com/wss/dragon/chat?auth=${encodeURIComponent(token)}&chatId=${encodeURIComponent(chatId)}`;
        } else {
            this.url = `wss://${window.location.hostname.replace('console', 'spyderweb')}/wss/dragon/chat?auth=${encodeURIComponent(token)}&chatId=${encodeURIComponent(chatId)}`;
        }

    }

    connect() {
        //console.log("Connecting to WebSocket URL:", this.url);

        this.socket = new WebSocket(this.url);

        this.socket.onopen = () => {
            if (this.onOpen) this.onOpen();
        };

        this.socket.onmessage = (event) => {
            this.onMessage(event.data);
        };

        this.socket.onerror = (error) => {
            if (this.onError) this.onError(error);
        };

        this.socket.onclose = () => {
            if (this.onClose) this.onClose();
        };
    }

    send(data: string) {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(data);
        }
    }

    disconnect() {
        if (this.socket) {
            this.socket.close();
        }
    }
}