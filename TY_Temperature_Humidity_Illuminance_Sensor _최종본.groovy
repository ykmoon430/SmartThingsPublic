/**
 *  TY Temperature/Humidity/Light Sensor WooBooung (base : iquix)
 *
 *  Copyright 2014,2021 SmartThings / WooBooung / clipman
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
import physicalgraph.zigbee.zcl.DataType
import groovy.json.JsonOutput

metadata {
	definition(name: "TY Temperature/Humidity/Illuminance Sensor", namespace: "circlecircle06391", author: "clipman", ocfDeviceType: "oic.d.thermostat",
		mnmn: "SmartThingsCommunity", vid: "7126d6bb-c047-37b5-be4d-848c66cb50fc") {
		capability "Configuration"
		capability "Battery"
		capability "Refresh"
		capability "Temperature Measurement"
		capability "Relative Humidity Measurement"
		capability "Illuminance Measurement"
		capability "Sensor"
		capability "circlecircle06391.statusBar"

		fingerprint profileId: "0104", deviceId: "0106", inClusters: "0000,0001,0400", outClusters: "0019", manufacturer: "_TYZB01_kvwjujy9", model: "TS0222", deviceJoinName: "Temp & Humi & Light Sensor"
	}

	preferences {
		input "tempOffset", type: "number", title: "Temperature offset", description: "Select how many degrees to adjust the temperature.", range: "-100..100", displayDuringSetup: false
		input "humidityOffset", type: "number", title: "Humidity offset", description: "Enter a percentage to adjust the humidity.", range: "*..*", displayDuringSetup: false
		input "wrongValuefilter", type: "boolean", title: "Filter: Temperature, Humidity wrong value", defaultValue: false
	}
}

def setStatus(status){
	sendEvent(name: "status", value: status)
}

def parse(String description) {
	//log.debug "description: $description"

	// getEvent will handle temperature and humidity
	Map descMap = zigbee.parseDescriptionAsMap(description)
	Map map = zigbee.getEvent(description)
	//log.debug "map: $map"

	if (!map) {
		if (descMap.clusterInt == 0x0001 && descMap.commandInt != 0x07 && descMap?.value) {
			if (descMap.attrInt == 0x0021) {
				map = getBatteryPercentageResult(Integer.parseInt(descMap.value,16))
			} else {
				map = getBatteryResult(Integer.parseInt(descMap.value, 16))
			}
		} else if (descMap?.clusterInt == zigbee.TEMPERATURE_MEASUREMENT_CLUSTER && descMap.commandInt == 0x07) {
			if (descMap.data[0] == "00") {
				log.debug "TEMP REPORTING CONFIG RESPONSE: $descMap"
				//sendEvent(name: "checkInterval", value: 60 * 12, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
			} else {
				log.warn "TEMP REPORTING CONFIG FAILED- error code: ${descMap.data[0]}"
			}
		} else if (description?.startsWith('illuminance:')) {
			def rawValue = ((description - "illuminance: ").trim())
			map.name = "illuminance"
			map.value = Math.ceil(zigbee.lux(rawValue as Integer))
		}
	} else if (map.name == "temperature") {
		if (description?.startsWith('temperature:')) {
			map.value = ((description - "temperature: ").trim()) as float
		}
		map.value = new BigDecimal((map.value as float) + (tempOffset ?: 0) as float).setScale(1, BigDecimal.ROUND_DOWN)
		map.descriptionText = temperatureScale == 'C' ? "$device.displayName was $map.value��C" : "$device.displayName was $map.value��F"
		map.translatable = true
	} else if (map.name == "humidity") {
		if (description?.startsWith('humidity:')) {
			map.value = description.substring(10, description.indexOf("%")) as float
		}
		map.value = new BigDecimal(map.value + (humidityOffset ?: 0) as float).setScale(1, BigDecimal.ROUND_DOWN)
	}

	//if (wrongValuefilter == true) { // not working.. why?
	if ("$wrongValuefilter" == "true") {
		map = handleErrorForTempHumi(map)
	}

	//log.debug "Parse returned $map"
	def results = map ? sendEvent(map) : [:]
 	setStatus(device.currentValue("temperature")+"��C/"+device.currentValue("humidity")+"%/"+device.currentValue("illuminance")+"lux")
	return results;
}

def handleErrorForTempHumi(map) {
	log.debug "handleErrorForTempHumi"
	def errorDiffValue = 10
	def mapValue = map?.value as Float
	if (map?.name == "temperature") {
		def currentTempValue = device.currentState("temperature").value.toFloat()
		def currentTempUnit = device.currentState("temperature").unit
		if (currentTempValue != null
			&& currentTempUnit == map.unit
			&& (map.value < 0 || Math.abs(currentTempValue - mapValue) > errorDiffValue)) {
				log.debug "handleErrorForTempHumi: skip temperature value $mapValue / currentValue $currentTempValue"
				map = [:]
			}
	} else if (map?.name == "humidity") {
		def currentHumiValue = device.currentState("humidity").value.toFloat()
		if (currenHumiValue != null
			&& Math.abs(currenHumiValue - mapValue) > errorDiffValue) {
				log.debug "handleErrorForTempHumi: skip humidity value $mapValue / currentValue $currentHumiValue"
				map = [:]
			}
	}

	return map
}

def installed() {
	log.debug "installed"
	configure()
}

def updated() {
	log.debug "updated"
	configure()
}

def getBatteryPercentageResult(rawValue) {
	log.debug "Battery Percentage rawValue = ${rawValue} -> ${rawValue / 2}%"
	def result = [:]

	if (0 <= rawValue && rawValue <= 200) {
		result.name = 'battery'
		result.translatable = true
		result.value = Math.round(rawValue / 2)
		result.descriptionText = "${device.displayName} battery was ${result.value}%"
	}

	return result
}

private Map getBatteryResult(rawValue) {
	log.debug 'Battery'
	def linkText = getLinkText(device)

	def result = [:]

	def volts = rawValue / 10
	if (!(rawValue == 0 || rawValue == 255)) {
		def minVolts = 2.1
		def maxVolts = 3.0
		def pct = (volts - minVolts) / (maxVolts - minVolts)
		def roundedPct = Math.round(pct * 100)
		if (roundedPct <= 0)
		roundedPct = 1
		result.value = Math.min(100, roundedPct)
		result.descriptionText = "${linkText} battery was ${result.value}%"
		result.name = 'battery'
	}

	return result
}

def refresh() {
	log.debug "refresh temperature, humidity, illuminence and battery"

	setStatus(device.currentValue("temperature")+"��C/"+device.currentValue("humidity")+"%/"+device.currentValue("illuminance")+"lux")
	return zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021)+
		zigbee.readAttribute(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000)+
		zigbee.readAttribute(zigbee.RELATIVE_HUMIDITY_CLUSTER, 0x0000) +
		zigbee.readAttribute(0x0400, 0x0000)
}

def configure() {
	log.debug "Configuring Reporting and Bindings."
	sendEvent(name: "DeviceWatch-Enroll", value: JsonOutput.toJson([protocol: "zigbee", scheme:"untracked"]), displayed: false)

	return refresh() +
		zigbee.configureReporting(zigbee.RELATIVE_HUMIDITY_CLUSTER, 0x0000, DataType.UINT16, 30, 300, 1*100) +
		zigbee.configureReporting(zigbee.TEMPERATURE_MEASUREMENT_CLUSTER, 0x0000, DataType.INT16, 30, 300, 0x1) +
		zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021, DataType.UINT8, 30, 21600, 0x1) +
		zigbee.configureReporting(0x0400, 0x0000, 0x21, 30, 3600, 0x15)
}
