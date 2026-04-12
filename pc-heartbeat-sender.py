"""
PC 端心跳发送脚本
用于 PC 启动时定期发送心跳到 MQTT，通知手机端 PC 在线

使用方法：
1. 安装依赖：pip install paho-mqtt
2. 运行脚本：python pc-heartbeat-sender.py
3. PC 启动时自动运行（可配置为开机自启）

功能：
- 每 30 秒发送一次心跳到 MQTT
- 心跳主题：fusion/pc/heartbeat
- 支持多个 PC 配置
- 断线自动重连

@author: Fusion
@version: 1.0
"""

import json
import socket
import time
import logging
from datetime import datetime
from typing import Optional

import paho.mqtt.client as mqtt
from paho.mqtt.client import MQTTMessage

# ==================== 配置参数 ====================

# MQTT Broker 配置（卧室手机 C）
MQTT_BROKER_HOST = "192.168.1.100"
MQTT_BROKER_PORT = 1883

# PC 配置
PC_ID = "main-pc"  # PC 唯一标识
PC_NAME = "主 PC"   # PC 名称
PC_HOSTNAME = socket.gethostname()  # 自动获取主机名

# 心跳配置
HEARTBEAT_INTERVAL = 30  # 心跳间隔（秒）

# MQTT 主题
TOPIC_HEARTBEAT = "fusion/pc/heartbeat"
TOPIC_STATUS_SUBSCRIBE = "fusion/pc/status"  # 订阅状态主题（可选）

# 日志配置
LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"
logging.basicConfig(
    level=logging.INFO,
    format=LOG_FORMAT,
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger(__name__)


class PCHeartbeatSender:
    """
    PC 心跳发送器
    
    功能：
    - 连接到 MQTT Broker
    - 定期发送心跳消息
    - 断线自动重连
    - 订阅状态主题（可选）
    """
    
    def __init__(self, pc_id: str, pc_name: str, broker_host: str, broker_port: int):
        """
        初始化心跳发送器
        
        Args:
            pc_id: PC 唯一标识
            pc_name: PC 名称
            broker_host: MQTT Broker 主机地址
            broker_port: MQTT Broker 端口
        """
        self.pc_id = pc_id
        self.pc_name = pc_name
        self.broker_host = broker_host
        self.broker_port = broker_port
        
        self.mqtt_client: Optional[mqtt.Client] = None
        self.running = False
        self.connected = False
        
        # 获取本机 IP 地址
        self.local_ip = self._get_local_ip()
        
        logger.info(f"心跳发送器初始化完成 - PC ID: {pc_id}, IP: {self.local_ip}")
    
    def _get_local_ip(self) -> str:
        """
        获取本机 IP 地址
        
        Returns:
            本机 IP 地址字符串
        """
        try:
            # 创建一个 UDP 连接来获取 IP（不会真正发送数据）
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((self.broker_host, self.broker_port))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception as e:
            logger.warning(f"获取 IP 地址失败：{e}")
            return "127.0.0.1"
    
    def _create_mqtt_client(self) -> mqtt.Client:
        """
        创建 MQTT 客户端
        
        Returns:
            MQTT 客户端实例
        """
        # 创建客户端（使用唯一 ID）
        client_id = f"{self.pc_id}-heartbeat-{int(time.time())}"
        client = mqtt.Client(client_id)
        
        # 配置回调
        client.on_connect = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.on_message = self._on_message
        
        # 配置自动重连
        client.reconnect_delay_set(delay=5, delay_max=60, exponential_backoff=True)
        
        return client
    
    def _on_connect(self, client, userdata, flags, rc):
        """
        连接成功回调
        
        Args:
            client: MQTT 客户端
            userdata: 用户数据
            flags: 连接标志
            rc: 返回码
        """
        if rc == 0:
            logger.info(f"MQTT 连接成功 - {self.broker_host}:{self.broker_port}")
            self.connected = True
            
            # 订阅状态主题（可选）
            try:
                client.subscribe(TOPIC_STATUS_SUBSCRIBE, qos=1)
                logger.info(f"已订阅状态主题：{TOPIC_STATUS_SUBSCRIBE}")
            except Exception as e:
                logger.warning(f"订阅状态主题失败：{e}")
            
            # 立即发送一次心跳
            self._send_heartbeat()
        else:
            logger.error(f"MQTT 连接失败 - 返回码：{rc}")
            self.connected = False
    
    def _on_disconnect(self, client, userdata, rc):
        """
        断开连接回调
        
        Args:
            client: MQTT 客户端
            userdata: 用户数据
            rc: 返回码
        """
        logger.warning(f"MQTT 连接断开 - 返回码：{rc}")
        self.connected = False
    
    def _on_message(self, client, userdata, msg: MQTTMessage):
        """
        收到消息回调
        
        Args:
            client: MQTT 客户端
            userdata: 用户数据
            msg: MQTT 消息
        """
        try:
            payload = msg.payload.decode('utf-8')
            logger.debug(f"收到消息 - 主题：{msg.topic}, 内容：{payload}")
            
            # 可以在这里处理来自手机端的命令
            # 例如：强制检测、更新配置等
            
        except Exception as e:
            logger.error(f"处理消息失败：{e}")
    
    def _send_heartbeat(self):
        """
        发送心跳消息到 MQTT
        """
        if not self.connected or self.mqtt_client is None:
            logger.warning("MQTT 未连接，无法发送心跳")
            return
        
        try:
            # 构建心跳消息
            heartbeat = {
                "pc_id": self.pc_id,
                "ip_address": self.local_ip,
                "timestamp": int(time.time() * 1000),
                "hostname": PC_HOSTNAME,
                "name": self.pc_name
            }
            
            # 序列化为 JSON
            payload = json.dumps(heartbeat, ensure_ascii=False)
            
            # 发布到 MQTT
            result = self.mqtt_client.publish(TOPIC_HEARTBEAT, payload, qos=1)
            
            if result.rc == mqtt.MQTT_ERR_SUCCESS:
                logger.info(f"❤️ 心跳已发送 - IP: {self.local_ip}")
            else:
                logger.error(f"发布心跳失败 - 返回码：{result.rc}")
            
        except Exception as e:
            logger.error(f"发送心跳异常：{e}")
    
    def _heartbeat_loop(self):
        """
        心跳循环（在独立线程运行）
        """
        logger.info(f"启动心跳循环 - 间隔：{HEARTBEAT_INTERVAL}秒")
        
        while self.running:
            try:
                # 等待下一次心跳
                time.sleep(HEARTBEAT_INTERVAL)
                
                if self.running and self.connected:
                    self._send_heartbeat()
                
            except Exception as e:
                logger.error(f"心跳循环异常：{e}")
        
        logger.info("心跳循环已退出")
    
    def start(self):
        """
        启动心跳发送器
        """
        if self.running:
            logger.warning("心跳发送器已在运行")
            return
        
        logger.info("启动心跳发送器...")
        self.running = True
        
        # 创建 MQTT 客户端
        self.mqtt_client = self._create_mqtt_client()
        
        # 启动心跳循环（在后台线程）
        import threading
        heartbeat_thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        heartbeat_thread.start()
        
        # 连接到 MQTT（阻塞调用，会自动重连）
        try:
            logger.info(f"开始连接 MQTT Broker: {self.broker_host}:{self.broker_port}")
            self.mqtt_client.connect(self.broker_host, self.broker_port, keepalive=60)
            
            # 启动网络循环（阻塞）
            self.mqtt_client.loop_forever()
            
        except Exception as e:
            logger.error(f"MQTT 连接异常：{e}")
            self.running = False
    
    def stop(self):
        """
        停止心跳发送器
        """
        if not self.running:
            return
        
        logger.info("停止心跳发送器...")
        self.running = False
        
        if self.mqtt_client is not None:
            try:
                self.mqtt_client.disconnect()
                self.mqtt_client = None
                logger.info("MQTT 已断开连接")
            except Exception as e:
                logger.error(f"断开 MQTT 连接失败：{e}")


def main():
    """
    主函数
    """
    logger.info("=" * 60)
    logger.info("PC 心跳发送器启动")
    logger.info(f"PC ID: {PC_ID}")
    logger.info(f"PC 名称：{PC_NAME}")
    logger.info(f"PC 主机名：{PC_HOSTNAME}")
    logger.info(f"MQTT Broker: {MQTT_BROKER_HOST}:{MQTT_BROKER_PORT}")
    logger.info(f"心跳间隔：{HEARTBEAT_INTERVAL}秒")
    logger.info("=" * 60)
    
    # 创建心跳发送器
    sender = PCHeartbeatSender(
        pc_id=PC_ID,
        pc_name=PC_NAME,
        broker_host=MQTT_BROKER_HOST,
        broker_port=MQTT_BROKER_PORT
    )
    
    # 启动（阻塞调用）
    try:
        sender.start()
    except KeyboardInterrupt:
        logger.info("收到中断信号，正在退出...")
        sender.stop()
    except Exception as e:
        logger.error(f"程序异常退出：{e}")
        sender.stop()


if __name__ == "__main__":
    main()
