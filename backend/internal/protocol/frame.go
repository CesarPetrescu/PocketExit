package protocol

import (
	"encoding/binary"
	"fmt"
	"io"
)

const MaxDatagramSize = 65507

func WriteDatagram(w io.Writer, payload []byte) error {
	if len(payload) > MaxDatagramSize {
		return fmt.Errorf("datagram too large: %d", len(payload))
	}
	var header [2]byte
	binary.BigEndian.PutUint16(header[:], uint16(len(payload)))
	if _, err := w.Write(header[:]); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

func ReadDatagram(r io.Reader) ([]byte, error) {
	var header [2]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		return nil, err
	}
	length := int(binary.BigEndian.Uint16(header[:]))
	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return nil, err
	}
	return payload, nil
}
