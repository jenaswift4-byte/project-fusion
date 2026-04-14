@echo off
cd /d "C:\Users\wang\Desktop\万物互联"
git config --global --unset http.proxy 2>nul
git config --global --unset https.proxy 2>nul
git push origin main > "C:\Users\wang\Desktop\万物互联\push-result.txt" 2>&1
echo DONE >> "C:\Users\wang\Desktop\万物互联\push-result.txt"
