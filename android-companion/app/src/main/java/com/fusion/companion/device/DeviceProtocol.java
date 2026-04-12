package com.fusion.companion.device;

import org.json.JSONObject;

/**
 * ESP32 设备 MQTT 协议定义
 * 
 * 定义所有 MQTT 主题、消息格式、命令类型和响应格式
 * 用于 Android 与 ESP32 设备之间的通信
 * 
 * @author Backend Architect
 * @version 1.0.0
 */
public class DeviceProtocol {

    // ==================== MQTT 主题定义 ====================
    
    /**
     * 设备控制主题
     */
    public static class ControlTopics {
        /** 控制命令下发主题 */
        public static final String CONTROL = "devices/{deviceId}/control";
        /** 设备状态上报主题 */
        public static final String STATUS = "devices/{deviceId}/status";
        /** 设备响应主题 */
        public static final String RESPONSE = "devices/{deviceId}/response";
        
        /**
         * 获取设备控制主题
         * @param deviceId 设备 ID
         * @return 控制主题
         */
        public static String getControlTopic(String deviceId) {
            return CONTROL.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取设备状态主题
         * @param deviceId 设备 ID
         * @return 状态主题
         */
        public static String getStatusTopic(String deviceId) {
            return STATUS.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取设备响应主题
         * @param deviceId 设备 ID
         * @return 响应主题
         */
        public static String getResponseTopic(String deviceId) {
            return RESPONSE.replace("{deviceId}", deviceId);
        }
    }
    
    /**
     * 传感器主题
     */
    public static class SensorTopics {
        /** 传感器数据主题 */
        public static final String SENSOR = "sensors/{deviceId}/{type}";
        
        /**
         * 获取传感器主题
         * @param deviceId 设备 ID
         * @param type 传感器类型（temperature, humidity, light, etc.）
         * @return 传感器主题
         */
        public static String getSensorTopic(String deviceId, String type) {
            return SENSOR.replace("{deviceId}", deviceId).replace("{type}", type);
        }
    }
    
    /**
     * 窗帘设备主题
     */
    public static class CurtainTopics {
        /** 打开窗帘 */
        public static final String OPEN = "curtain/{deviceId}/open";
        /** 关闭窗帘 */
        public static final String CLOSE = "curtain/{deviceId}/close";
        /** 停止窗帘 */
        public static final String STOP = "curtain/{deviceId}/stop";
        /** 设置窗帘位置 */
        public static final String SET = "curtain/{deviceId}/set";
        
        /**
         * 获取打开窗帘主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getOpenTopic(String deviceId) {
            return OPEN.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取关闭窗帘主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getCloseTopic(String deviceId) {
            return CLOSE.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取停止窗帘主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getStopTopic(String deviceId) {
            return STOP.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取设置窗帘位置主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getSetTopic(String deviceId) {
            return SET.replace("{deviceId}", deviceId);
        }
    }
    
    /**
     * 灯光设备主题
     */
    public static class LightTopics {
        /** 开灯 */
        public static final String ON = "light/{deviceId}/on";
        /** 关灯 */
        public static final String OFF = "light/{deviceId}/off";
        /** 调光 */
        public static final String DIM = "light/{deviceId}/dim";
        /** 调色 */
        public static final String COLOR = "light/{deviceId}/color";
        
        /**
         * 获取开灯主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getOnTopic(String deviceId) {
            return ON.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取关灯主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getOffTopic(String deviceId) {
            return OFF.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取调光主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getDimTopic(String deviceId) {
            return DIM.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取调色主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getColorTopic(String deviceId) {
            return COLOR.replace("{deviceId}", deviceId);
        }
    }
    
    /**
     * 插座设备主题
     */
    public static class SocketTopics {
        /** 打开插座 */
        public static final String ON = "socket/{deviceId}/on";
        /** 关闭插座 */
        public static final String OFF = "socket/{deviceId}/off";
        /** 插座状态 */
        public static final String STATUS = "socket/{deviceId}/status";
        
        /**
         * 获取打开插座主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getOnTopic(String deviceId) {
            return ON.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取关闭插座主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getOffTopic(String deviceId) {
            return OFF.replace("{deviceId}", deviceId);
        }
        
        /**
         * 获取插座状态主题
         * @param deviceId 设备 ID
         * @return 主题
         */
        public static String getStatusTopic(String deviceId) {
            return STATUS.replace("{deviceId}", deviceId);
        }
    }

    // ==================== 命令类型定义 ====================
    
    /**
     * 通用命令类型
     */
    public static class CommandTypes {
        /** 打开 */
        public static final String OPEN = "open";
        /** 关闭 */
        public static final String CLOSE = "close";
        /** 停止 */
        public static final String STOP = "stop";
        /** 切换 */
        public static final String TOGGLE = "toggle";
        /** 设置 */
        public static final String SET = "set";
        /** 查询 */
        public static final String QUERY = "query";
    }
    
    /**
     * 窗帘命令类型
     */
    public static class CurtainCommands {
        /** 打开窗帘 */
        public static final String OPEN = "open";
        /** 关闭窗帘 */
        public static final String CLOSE = "close";
        /** 停止窗帘 */
        public static final String STOP = "stop";
        /** 设置位置 */
        public static final String SET_POSITION = "set_position";
    }
    
    /**
     * 灯光命令类型
     */
    public static class LightCommands {
        /** 开灯 */
        public static final String ON = "on";
        /** 关灯 */
        public static final String OFF = "off";
        /** 调光 */
        public static final String DIM = "dim";
        /** 调色 */
        public static final String COLOR = "color";
        /** 设置色温 */
        public static final String SET_COLOR_TEMP = "set_color_temp";
    }
    
    /**
     * 插座命令类型
     */
    public static class SocketCommands {
        /** 打开插座 */
        public static final String ON = "on";
        /** 关闭插座 */
        public static final String OFF = "off";
        /** 查询状态 */
        public static final String QUERY_STATUS = "query_status";
    }

    // ==================== 消息格式定义 ====================
    
    /**
     * 控制命令消息格式
     * 
     * JSON 格式:
     * {
     *   "command": "open",
     *   "params": {
     *     "position": 50
     *   },
     *   "timestamp": 1234567890
     * }
     */
    public static class ControlMessage {
        /** 命令类型 */
        public String command;
        /** 命令参数 */
        public JSONObject params;
        /** 时间戳（毫秒） */
        public long timestamp;
        
        /**
         * 创建控制命令消息
         * @param command 命令类型
         * @param params 命令参数
         * @return JSON 字符串
         */
        public static String createMessage(String command, JSONObject params) {
            JSONObject message = new JSONObject();
            message.put("command", command);
            if (params != null) {
                message.put("params", params);
            }
            message.put("timestamp", System.currentTimeMillis());
            return message.toString();
        }
        
        /**
         * 创建控制命令消息（无参数）
         * @param command 命令类型
         * @return JSON 字符串
         */
        public static String createMessage(String command) {
            return createMessage(command, null);
        }
        
        /**
         * 解析控制命令消息
         * @param json JSON 字符串
         * @return ControlMessage 对象
         */
        public static ControlMessage parseMessage(String json) {
            try {
                JSONObject obj = new JSONObject(json);
                ControlMessage msg = new ControlMessage();
                msg.command = obj.getString("command");
                msg.params = obj.optJSONObject("params");
                msg.timestamp = obj.getLong("timestamp");
                return msg;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }
    
    /**
     * 状态上报消息格式
     * 
     * JSON 格式:
     * {
     *   "status": "open",
     *   "position": 50,
     *   "battery": 85,
     *   "timestamp": 1234567890
     * }
     */
    public static class StatusMessage {
        /** 设备状态 */
        public String status;
        /** 位置（窗帘等） */
        public int position;
        /** 电量百分比 */
        public int battery;
        /** 时间戳（毫秒） */
        public long timestamp;
        /** 其他数据 */
        public JSONObject data;
        
        /**
         * 创建状态上报消息
         * @param status 设备状态
         * @param position 位置（可选）
         * @param battery 电量（可选）
         * @param data 其他数据（可选）
         * @return JSON 字符串
         */
        public static String createMessage(String status, Integer position, Integer battery, JSONObject data) {
            JSONObject message = new JSONObject();
            message.put("status", status);
            if (position != null) {
                message.put("position", position);
            }
            if (battery != null) {
                message.put("battery", battery);
            }
            if (data != null) {
                message.put("data", data);
            }
            message.put("timestamp", System.currentTimeMillis());
            return message.toString();
        }
        
        /**
         * 解析状态上报消息
         * @param json JSON 字符串
         * @return StatusMessage 对象
         */
        public static StatusMessage parseMessage(String json) {
            try {
                JSONObject obj = new JSONObject(json);
                StatusMessage msg = new StatusMessage();
                msg.status = obj.getString("status");
                msg.position = obj.optInt("position", -1);
                msg.battery = obj.optInt("battery", -1);
                msg.data = obj.optJSONObject("data");
                msg.timestamp = obj.getLong("timestamp");
                return msg;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }
    
    /**
     * 响应消息格式
     * 
     * JSON 格式:
     * {
     *   "success": true,
     *   "message": "OK",
     *   "data": {}
     * }
     */
    public static class ResponseMessage {
        /** 是否成功 */
        public boolean success;
        /** 消息内容 */
        public String message;
        /** 响应数据 */
        public JSONObject data;
        
        /**
         * 创建成功响应
         * @param message 消息内容
         * @param data 响应数据
         * @return JSON 字符串
         */
        public static String createSuccess(String message, JSONObject data) {
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", message);
            if (data != null) {
                response.put("data", data);
            }
            return response.toString();
        }
        
        /**
         * 创建失败响应
         * @param message 错误消息
         * @return JSON 字符串
         */
        public static String createError(String message) {
            JSONObject response = new JSONObject();
            response.put("success", false);
            response.put("message", message);
            return response.toString();
        }
        
        /**
         * 解析响应消息
         * @param json JSON 字符串
         * @return ResponseMessage 对象
         */
        public static ResponseMessage parseMessage(String json) {
            try {
                JSONObject obj = new JSONObject(json);
                ResponseMessage msg = new ResponseMessage();
                msg.success = obj.getBoolean("success");
                msg.message = obj.getString("message");
                msg.data = obj.optJSONObject("data");
                return msg;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    // ==================== 设备类型定义 ====================
    
    /**
     * 支持的设备类型
     */
    public static class DeviceTypes {
        /** 窗帘 */
        public static final String CURTAIN = "curtain";
        /** 灯光 */
        public static final String LIGHT = "light";
        /** 插座 */
        public static final String SOCKET = "socket";
        /** 传感器 */
        public static final String SENSOR = "sensor";
        /** 开关 */
        public static final String SWITCH = "switch";
        /** 温控器 */
        public static final String THERMOSTAT = "thermostat";
        /** 门锁 */
        public static final String LOCK = "lock";
    }
    
    /**
     * 传感器类型
     */
    public static class SensorTypes {
        /** 温度传感器 */
        public static final String TEMPERATURE = "temperature";
        /** 湿度传感器 */
        public static final String HUMIDITY = "humidity";
        /** 光照传感器 */
        public static final String LIGHT = "light";
        /** 运动传感器 */
        public static final String MOTION = "motion";
        /** 门窗传感器 */
        public static final String CONTACT = "contact";
        /** 烟雾传感器 */
        public static final String SMOKE = "smoke";
        /** 气体传感器 */
        public static final String GAS = "gas";
    }
}
