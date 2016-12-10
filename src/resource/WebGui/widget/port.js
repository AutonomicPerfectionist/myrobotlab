/*


*/
angular.module('mrlapp.service').directive('port', ['$compile', 'mrl', '$log', function($compile, mrl, $log) {
    return {
        restrict: "E",
        templateUrl: 'widget/port.html',
        scope: {
            serviceName: '@',
            portDirectiveScope : "=ngModel"
        },
        // scope: true,
        link: function(scope, element) {
            var _self = this;
            var name = scope.serviceName;
            scope.service = mrl.getService(name);
            _self.updateState = function(service) {
                scope.service = service;
                scope.isConnected = (scope.service.portName != null );
                scope.isConnectedImage = (scope.service.portName != null ) ? "connected" : "disconnected";
                scope.connectText = (scope.service.portName == null ) ? "connect" : "disconnect";
                if (scope.isConnected) {
                    scope.portName = scope.service.portName;
                } else {
                    scope.portName = scope.service.lastPortName;
                }

                // getting the data back from the directive to the controller
                // scope.obj.portName = scope.portName;
            }
            _self.updateState(scope.service);
            _self.onMsg = function(inMsg) {
                //console.log('CALLBACK - ' + msg.method);
                switch (inMsg.method) {
                case 'onPortNames':
                    scope.possiblePorts = inMsg.data[0];
                    scope.$apply();
                    break;
                case 'onRefresh':
                    scope.possiblePorts = inMsg.data[0];
                    scope.$apply();
                    break;
                case 'onState':
                    // backend update 
                    _self.updateState(inMsg.data[0]);
                    scope.$apply();
                    break;
                case 'onStats':
                    // backend update 
                    //_self.updateState(msg.data[0]);
                    scope.stats = inMsg.data[0];
                    scope.$apply();
                    break;
                default:
                    console.log("ERROR - unhandled method " + inMsg.method);
                    break;
                }
            }
            // onMsg
            ;
            scope.connect = function(portName, rate, dataBits, stopBits, parity) {
                mrl.sendTo(scope.service.name, 'connect', portName, rate, dataBits, stopBits, parity);
            }
            ;
            scope.refresh = function() {
                mrl.sendTo(scope.service.name, 'refresh');
            }

            scope.settings = function() {
                //mrl.sendTo(scope.service.name, 'disconnect')
            }
            ;
            // subscribes
            mrl.subscribeToServiceMethod(_self.onMsg, name, 'publishPortNames');
            mrl.subscribeToServiceMethod(_self.onMsg, name, 'refresh');
            mrl.subscribeToServiceMethod(_self.onMsg, name, 'publishState');
            mrl.subscribeToServiceMethod(_self.onMsg, name, 'publishStats');
            scope.portDirectiveScope = scope;
        }
    };
}
]);