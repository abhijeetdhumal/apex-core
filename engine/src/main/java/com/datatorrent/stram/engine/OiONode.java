/*
 *  Copyright (c) 2012-2013 DataTorrent, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.stram.engine;

import java.util.Iterator;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.commons.lang.UnhandledException;

import com.datatorrent.api.Operator;
import com.datatorrent.api.Operator.InputPort;
import com.datatorrent.api.Sink;
import com.datatorrent.stram.plan.logical.Operators.PortContextPair;

import com.datatorrent.stram.tuple.Tuple;

/**
 * OiONode is driver for the OiO (ThreadLocal) operator.
 *
 * It mostly replicates the functionality of the GenericNode but the logic here is
 * a lot simpler as most of the validation is already handled by the upstream operator.
 *
 * @author Chetan Narsude <chetan@datatorrent.com>
 * @since 0.3.5
 */
public class OiONode extends GenericNode implements Sink<Tuple>
{
  private long lastResetWindowId = WindowGenerator.MIN_WINDOW_ID - 1;
  private long lastEndStreamWindowId = WindowGenerator.MAX_WINDOW_ID - 1;
  private int expectingEndWindows = 0;

  public OiONode(Operator operator)
  {
    super(operator);
  }

  @Override
  public void put(Tuple t)
  {
    switch (t.getType()) {
      case BEGIN_WINDOW:
        expectingEndWindows++;
        if (t.getWindowId() != currentWindowId) {
          currentWindowId = t.getWindowId();
          for (int s = sinks.length; s-- > 0;) {
            sinks[s].put(t);
          }
          if (applicationWindowCount == 0) {
            insideWindow = true;
            operator.beginWindow(currentWindowId);
          }
        }
        break;

      case END_WINDOW:
        if (--expectingEndWindows == 0) {
          processEndWindow(t);
        }
        break;

      case CHECKPOINT:
        if (lastCheckpointedWindowId < currentWindowId && !checkpoint) {
          if (checkpointWindowCount == 0) {
            if (checkpoint(currentWindowId)) {
              lastCheckpointedWindowId = currentWindowId;
            }
          }
          else {
            checkpoint = true;
          }
          for (int s = sinks.length; s-- > 0;) {
            sinks[s].put(t);
          }
        }
        break;

      case RESET_WINDOW:
        if (t.getWindowId() != lastResetWindowId) {
          lastResetWindowId = t.getWindowId();
          for (int s = sinks.length; s-- > 0;) {
            sinks[s].put(t);
          }
        }
        break;

      case END_STREAM:
        if (lastEndStreamWindowId != t.getWindowId()) {
          lastEndStreamWindowId = t.getWindowId();

          for (Iterator<Entry<String, SweepableReservoir>> it = inputs.entrySet().iterator(); it.hasNext();) {
            Entry<String, SweepableReservoir> e = it.next();
            PortContextPair<InputPort<?>> pcpair = descriptor.inputPorts.get(e.getKey());
            if (pcpair != null) {
              pcpair.component.setConnected(false);
            }
          }
          inputs.clear();

          Iterator<DeferredInputConnection> dici = deferredInputConnections.iterator();
          while (dici.hasNext()) {
            DeferredInputConnection dic = dici.next();
            if (!inputs.containsKey(dic.portname)) {
              dici.remove();
              connectInputPort(dic.portname, dic.reservoir);
            }
          }

          if (inputs.isEmpty()) {
            if (insideWindow) {
              expectingEndWindows = 0;
              processEndWindow(null);
            }
            emitEndStream();
          }
        }
        break;

      default:
        throw new UnhandledException("Unrecognized Control Tuple", new IllegalArgumentException(t.toString()));
    }
  }

  @Override
  public int getCount(boolean reset)
  {
    throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
  }

  private static final Logger logger = LoggerFactory.getLogger(OiONode.class);
}