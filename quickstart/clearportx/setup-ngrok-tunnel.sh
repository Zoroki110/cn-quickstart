#!/bin/bash

# Installer ngrok si nécessaire
if ! command -v ngrok &> /dev/null; then
    echo "Installation de ngrok..."
    curl -s https://ngrok-agent.s3.amazonaws.com/ngrok.asc | sudo tee /etc/apt/trusted.gpg.d/ngrok.asc >/dev/null
    echo "deb https://ngrok-agent.s3.amazonaws.com buster main" | sudo tee /etc/apt/sources.list.d/ngrok.list
    sudo apt update && sudo apt install ngrok
fi

# Démarrer le backend
echo "Démarrage du backend..."
cd /root/cn-quickstart/quickstart/clearportx
./start-backend-production.sh &
sleep 30

# Créer tunnel ngrok
echo "Création du tunnel ngrok..."
ngrok http 8080 --log-level=info
