package model

import "time"

type Policy string

const (
	PolicyAuto              Policy = "AUTO"
	PolicyWiFiOnly          Policy = "WIFI_ONLY"
	PolicyCellularOnly      Policy = "CELLULAR_ONLY"
	PolicyWiFiPreferred     Policy = "WIFI_PREFERRED"
	PolicyCellularPreferred Policy = "CELLULAR_PREFERRED"
)

func (p Policy) Valid() bool {
	switch p {
	case PolicyAuto, PolicyWiFiOnly, PolicyCellularOnly, PolicyWiFiPreferred, PolicyCellularPreferred:
		return true
	default:
		return false
	}
}

type NetworkState struct {
	Available     bool     `json:"available"`
	Validated     bool     `json:"validated"`
	Metered       bool     `json:"metered"`
	InterfaceName string   `json:"interface_name,omitempty"`
	Addresses     []string `json:"addresses,omitempty"`
	DNSServers    []string `json:"dns_servers,omitempty"`
	MTU           int      `json:"mtu,omitempty"`
	DownKbps      int      `json:"down_kbps,omitempty"`
	UpKbps        int      `json:"up_kbps,omitempty"`
}

type HeartbeatRequest struct {
	NodeID               string       `json:"node_id"`
	DeviceName           string       `json:"device_name"`
	AppVersion           string       `json:"app_version"`
	ControlPolicy        Policy       `json:"control_policy"`
	ExitPolicy           Policy       `json:"exit_policy"`
	ActiveControlNetwork string       `json:"active_control_network"`
	TransportProtocol    string       `json:"transport_protocol,omitempty"`
	WiFi                 NetworkState `json:"wifi"`
	Cellular             NetworkState `json:"cellular"`
	BatteryPercent       int          `json:"battery_percent"`
	Charging             bool         `json:"charging"`
	ActiveCircuits       int          `json:"active_circuits"`
	BytesUp              uint64       `json:"bytes_up"`
	BytesDown            uint64       `json:"bytes_down"`
}

type Node struct {
	NodeID               string       `json:"node_id"`
	DeviceName           string       `json:"device_name"`
	AppVersion           string       `json:"app_version"`
	Enabled              bool         `json:"enabled"`
	Online               bool         `json:"online"`
	ControlPolicy        Policy       `json:"control_policy"`
	ExitPolicy           Policy       `json:"exit_policy"`
	ActiveControlNetwork string       `json:"active_control_network"`
	TransportProtocol    string       `json:"transport_protocol,omitempty"`
	WiFi                 NetworkState `json:"wifi"`
	Cellular             NetworkState `json:"cellular"`
	BatteryPercent       int          `json:"battery_percent"`
	Charging             bool         `json:"charging"`
	ActiveCircuits       int          `json:"active_circuits"`
	BytesUp              uint64       `json:"bytes_up"`
	BytesDown            uint64       `json:"bytes_down"`
	LastSeen             time.Time    `json:"last_seen"`
}

type CommandType string

const (
	CommandOpenTCP      CommandType = "open_tcp"
	CommandOpenUDP      CommandType = "open_udp"
	CommandClose        CommandType = "close"
	CommandPolicyUpdate CommandType = "policy_update"
)

type Command struct {
	Type          CommandType `json:"type"`
	CircuitID     string      `json:"circuit_id,omitempty"`
	TargetHost    string      `json:"target_host,omitempty"`
	TargetPort    int         `json:"target_port,omitempty"`
	ExitPolicy    Policy      `json:"exit_policy,omitempty"`
	ControlPolicy Policy      `json:"control_policy,omitempty"`
	AllowPrivate  bool        `json:"allow_private,omitempty"`
}

type CircuitProtocol string

const (
	ProtocolTCP CircuitProtocol = "tcp"
	ProtocolUDP CircuitProtocol = "udp"
)

type CircuitStatus string

const (
	CircuitPending CircuitStatus = "pending"
	CircuitOpen    CircuitStatus = "open"
	CircuitFailed  CircuitStatus = "failed"
	CircuitClosed  CircuitStatus = "closed"
)

type CircuitView struct {
	ID         string          `json:"id"`
	NodeID     string          `json:"node_id"`
	Protocol   CircuitProtocol `json:"protocol"`
	TargetHost string          `json:"target_host"`
	TargetPort int             `json:"target_port"`
	ExitPolicy Policy          `json:"exit_policy"`
	Status     CircuitStatus   `json:"status"`
	Error      string          `json:"error,omitempty"`
	BytesUp    uint64          `json:"bytes_up"`
	BytesDown  uint64          `json:"bytes_down"`
	CreatedAt  time.Time       `json:"created_at"`
	UpdatedAt  time.Time       `json:"updated_at"`
}
