package com.orientechnologies.orient.client.remote.message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.orientechnologies.orient.client.remote.OBinaryResponse;
import com.orientechnologies.orient.client.remote.OStorageRemoteSession;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelDataInput;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelDataOutput;

public class OImportResponse implements OBinaryResponse {
  private List<String> messages = new ArrayList<>();

  public OImportResponse(List<String> messages) {
    this.messages = messages;
  }

  public OImportResponse() {
  }

  @Override
  public void write(OChannelDataOutput channel, int protocolVersion, String recordSerializer) throws IOException {
    for (String string : messages) {
      channel.writeString(string);
    }
    channel.writeString(null);
  }

  @Override
  public void read(OChannelDataInput network, OStorageRemoteSession session) throws IOException {
    String message;
    while ((message = network.readString()) != null) {
      messages.add(message);
    }
  }

  public List<String> getMessages() {
    return messages;
  }
}