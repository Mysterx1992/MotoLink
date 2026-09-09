package it.motolink.app

data class VogeNavModel(
    var routeRemainDistM: Int = 0,
    var routeRemainTimeSec: Int = 0,
    var arriveRemainSec: Int = 0,
    var curRoadRemainDistM: Int = 0,
    var curRoadName: String = "",
    var nextRoadDirection: Int = 0,
    var nextRoadName: String = "",
    var roadFlag: Int = 0,
    var naviOnOff: Int = 0,
    var annularDegrees: Int = 0,
    var realDistance: Boolean = false,
    var sourceTitle: String = "",
    var sourceSubText: String = ""
)
