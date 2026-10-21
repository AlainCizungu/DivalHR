"""MVP-101 (ADR 0027): the first governed Dival AI capability, a grounded operational summary.

The model never writes factual prose. Core sends a closed list of candidate facts; a planner (the
deterministic one, or an approved structured-output model) only selects and orders fact ids; the
text is rendered here from reviewed, versioned templates.
"""
