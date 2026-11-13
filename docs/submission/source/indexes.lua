function Pandoc(doc)
  local sections, figures, tables = {}, {}, {}
  local function entry(list, label, target)
    list[#list + 1] = pandoc.Plain{pandoc.Link(label, "#" .. target)}
  end
  for _, block in ipairs(doc.blocks) do
    if block.t == "Header" and block.level == 1 then
      entry(sections, block.content, block.identifier)
    elseif block.t == "Figure" or block.t == "Table" then
      local list = block.t == "Figure" and figures or tables
      local label = pandoc.utils.stringify(block.caption.long)
      if block.t == "Figure" then
        label = "Figure " .. (#list + 1) .. ". " .. label
        block.caption.long = {pandoc.Plain{pandoc.Str(label)}}
      end
      block.identifier = block.t:lower() .. "-" .. (#list + 1)
      entry(list, label, block.identifier)
    end
  end

  local result = pandoc.List()
  for _, block in ipairs(doc.blocks) do
    if block.t == "Header" and block.identifier == "abstract" then
      for _, index in ipairs{{"Contents", sections}, {"List of Figures", figures}, {"List of Tables", tables}} do
        result:insert(pandoc.Header(1, index[1], pandoc.Attr("", {"unnumbered"})))
        result:insert(pandoc.Div(index[2], pandoc.Attr("", {"submission-index"})))
      end
    end
    result:insert(block)
  end
  doc.blocks = result
  return doc
end
